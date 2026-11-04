package com.zhishiku.kb.app.space.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.DocumentAdminDeleteResult;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端整文档删除：按文档<b>当前所属空间</b>的 manage 或 {@code space:manage-all} 判定。
 *
 * <h2>为什么不走用户端删除</h2>
 * 空间 manage 不蕴含 edit，用户端 {@code DELETE /api/documents/{id}} 只按 edit 判定并排除
 * 已归档空间；空间管理员要删本空间的文档只能先把 edit 授给自己——一次被审计记录下来的
 * 自我扩权。本服务提供按管理资格判定的入口，删除效果则与用户端完全一致：两条入口共用
 * {@link DocumentManagementService#deleteAuthorized}。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li>请求形状（缺请求标识、非正整数）先拒，不读库。</li>
 *   <li>停用主体先拒：放在任何读取之前，停用用户不能借 404/403 的差异探出文档存在性。</li>
 *   <li>幂等重放：命中审计后对首次删除时所属空间重新判定 manage，再返回首次回执；
 *       不再读取文档（它已删除）。</li>
 *   <li>预读归属（不加锁）→ 对实际归属判 manage → 调用共用主体，软删除带空间前置条件，
 *       文档在判定后被搬走时更新 0 行并以 409 拒绝。先锁文档行再判定会把锁顺序改成
 *       "文档 → 任务"，与用户端删除及 Worker 发布相反。</li>
 *   <li>写审计：{@code before_state} 只含文档 ID、所属空间与归档状态。</li>
 * </ol>
 *
 * <h2>为什么无权与不存在统一 404</h2>
 * 管理清单对两者返回同形状空页，面板在无权时根本不显示删除区块；若删除返回 403，
 * 不管理该空间的人可以借 403/404 差异探出文档存在性。
 */
@Service
public class DocumentAdminDeletionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAdminDeletionService.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int REQUEST_ID_LIMIT = 128;

    /**
     * 不存在、已删除与无权管理共用这一条消息，响应形状不得区分三者。
     */
    static final String NOT_FOUND_MESSAGE = "文档不存在，或当前用户无权管理其所属空间";

    private final MetadataMapper metadata;
    private final SpaceMapper spaces;
    private final SpaceAuthorizer authorizer;
    private final IdentityAuditService audit;
    private final DocumentManagementService documents;

    public DocumentAdminDeletionService(MetadataMapper metadata, SpaceMapper spaces, SpaceAuthorizer authorizer,
                                        IdentityAuditService audit, DocumentManagementService documents) {
        this.metadata = metadata;
        this.spaces = spaces;
        this.authorizer = authorizer;
        this.audit = audit;
        this.documents = documents;
    }

    /**
     * 删除整份文档及其全部版本。
     *
     * @param documentId 文档内部 ID
     * @param requestId  请求幂等标识，不可为空
     * @return 回执；重放时为首次删除的回执且 {@code replayed} 为真
     */
    @Transactional
    public DocumentAdminDeleteResult delete(long documentId, String requestId) {
        String request = requireRequestId(requestId);
        if (documentId <= 0) {
            throw IdentityException.badRequest("文档 ID 必须为正整数");
        }
        authorizer.requireEnabledUser();

        IdentityAuditRecord previous = audit.existing(AuditScope.DOCUMENT_ADMIN_DELETE, request);
        if (previous != null) {
            return replay(documentId, previous);
        }

        DocumentEntity hint = metadata.document(documentId);
        if (hint == null || hint.getSpaceId() == null || "deleted".equals(hint.getStatus())) {
            throw hidden();
        }
        long spaceId = hint.getSpaceId();
        long actor = requireManagerOrHide(spaceId);
        boolean archived = isArchived(spaceId);

        try {
            documents.deleteAuthorized(documentId, actor, spaceId);
        } catch (ApiException shared) {
            throw translate(shared);
        }

        audit.appendSuccess(AuditScope.DOCUMENT_ADMIN_DELETE, request, actor, documentId, "delete",
                write(new Snapshot(documentId, spaceId, archived)), "{}");
        log.info("管理端删除文档完成: documentId={} spaceId={} archived={}", documentId, spaceId, archived);
        return new DocumentAdminDeleteResult(documentId, spaceId, false);
    }

    /**
     * 重放先核对操作者对首次删除时所属空间的管理资格，再返回首次回执。
     *
     * <p>不读取文档：它已被删除，按当前归属判定没有对象；与搬迁"重放前对原源空间判定
     * manage"同一思路。
     */
    private DocumentAdminDeleteResult replay(long documentId, IdentityAuditRecord previous) {
        Snapshot snapshot = read(previous.getBeforeState());
        if (snapshot == null || snapshot.documentId() != documentId
                || (previous.getTargetId() != null && previous.getTargetId() != documentId)) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "请求标识已用于其他文档的删除，请更换请求标识");
        }
        requireManagerOrHide(snapshot.spaceId());
        return new DocumentAdminDeleteResult(documentId, snapshot.spaceId(), true);
    }

    /**
     * 要求目标空间的管理资格；资格不足时抛出与不存在同形的 404。
     *
     * <p>只改写 {@code FORBIDDEN}：停用主体抛出的是 {@code DISABLED}，含义不同，原样传播。
     */
    private long requireManagerOrHide(long spaceId) {
        try {
            return authorizer.requireSpaceManager(spaceId);
        } catch (IdentityException denied) {
            if (denied.errorCode() != IdentityErrorCode.FORBIDDEN) {
                throw denied;
            }
            throw hidden();
        }
    }

    /**
     * 归档状态只进审计快照；空间硬删除时查不到行，但有文档行引用的空间不会被删，
     * 这里按未归档记录而不是拒绝删除。
     */
    private boolean isArchived(long spaceId) {
        SpaceRecord space = spaces.findRawById(spaceId);
        return space != null && Boolean.TRUE.equals(space.getArchived());
    }

    private static IdentityException hidden() {
        return IdentityException.notFound(NOT_FOUND_MESSAGE);
    }

    /**
     * 共用主体抛出的是通用 {@link ApiException}，不带错误分类，管理写入口的 reasonCode 投影
     * 只认身份领域异常；这里按状态改写：404 与本地判定同形（不区分已删除与无权），
     * 409 归入通用 CONFLICT。其他状态不属于共用主体的契约，原样传播。
     */
    private static RuntimeException translate(ApiException shared) {
        if (shared instanceof IdentityException) {
            return shared;
        }
        if (shared.httpStatus() == 404) {
            return hidden();
        }
        if (shared.httpStatus() == 409) {
            return IdentityException.conflict(IdentityErrorCode.CONFLICT, "文档归属已变化，请重新读取后再决定是否删除");
        }
        return shared;
    }

    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 requestId");
        }
        String normalized = requestId.trim();
        if (normalized.length() > REQUEST_ID_LIMIT) {
            throw IdentityException.badRequest("请求标识长度不能超过 " + REQUEST_ID_LIMIT);
        }
        return normalized;
    }

    private static String write(Snapshot snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计快照序列化失败", e);
        }
    }

    private static Snapshot read(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Snapshot snapshot = JSON.readValue(raw, Snapshot.class);
            return snapshot.documentId() > 0 && snapshot.spaceId() > 0 ? snapshot : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 审计 {@code before_state}。字段名与 {@code AuditSummaryProjector} 的投影规则一致。
     */
    private record Snapshot(long documentId, long spaceId, boolean spaceArchived) {
    }
}
