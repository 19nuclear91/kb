package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.service.ReviewCancellationService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataCommands;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import com.zhishiku.kb.app.space.mapper.DocumentAclMapper;
import com.zhishiku.kb.app.space.mapper.DocumentRelocationMapper;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.DocumentAclEntry;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.model.SpaceViewPrincipal;
import com.zhishiku.kb.common.security.DocumentRelocationResult;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PrincipalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.LongStream;

/**
 * 文档搬迁：把一篇文档迁入另一个知识空间。
 *
 * <h2>为什么一次搬迁必须做四件事</h2>
 * 空间是授权主单位，所以"换空间"是一次授权变更，而不是一次元数据编辑。同一事务内
 * 必须完成：推进 {@code kb_document.space_id}（权威归属）、同步
 * {@code kb_doc_chunk.space_id}（检索用的冗余副本）、<b>清空该文档的全部例外 ACL</b>、
 * 写审计。任何一步失败整体回滚——半完成的搬迁就是一次越权。
 *
 * <p>两条读路径口径不同是这件事最容易漏的地方：列表/详情/下载/预览经
 * {@code PermissionScope} 读权威值，检索（{@code SearchMapper.xml}）读冗余副本。
 * 只改权威值会让文档继续被<b>原空间</b>的人搜到，同时对新空间的人搜不到。
 *
 * <h2>为什么清空而不是筛选保留</h2>
 * 例外授权的鉴权依据是"文档当前所属空间的管理资格"。搬迁后原空间管理员已失去这个
 * 资格，但其授出的记录仍留在 {@code kb_document_acl} 里继续生效——等于让一个已经失去
 * 资格的人保留对文档的授权遗泽。筛选式保留会留下一批没有任何人在新空间重新授出过
 * 的授权，且很难向管理员解释哪些留下了；清空加回执是可解释的。
 *
 * <h2>为什么请求必须声明 fromSpaceId</h2>
 * 搬迁的全部鉴权压在源空间上。若文档已被他人从 A 搬到 C，一个带着"我以为它在 A"的
 * 请求打进来，服务端会去校验 manage(C)；调用方若恰好也管 C，这次校验<b>会通过</b>
 * ——于是执行了一次他从未打算做的搬迁，并清掉了一批他从未看见的例外。乐观锁版本列
 * 只能告诉他"有人动过"，{@code fromSpaceId} 能让他把赌注写明，服务端据此拒绝。
 *
 * @see DocumentAclService 文档级例外授权的授予与撤销，其清理义务由本服务兑付
 */
@Service
public class DocumentRelocationService {

    private static final Logger log = LoggerFactory.getLogger(DocumentRelocationService.class);

    /**
     * 回执与审计里例外摘要的条数上限。
     *
     * <p>存在上限是为了防审计 JSON 失控。例外按设计应当稀少；若某文档的例外多到被
     * 截断，那本身就是该空间组织方式出了问题的信号，值得在审计里看得见——所以截断
     * 带标记，而不是静悄悄地少几条。
     */
    static final int CLEARED_SUMMARY_LIMIT = 50;

    private final DocumentRelocationMapper mapper;
    private final DocumentAclMapper acls;
    private final SpaceMapper spaces;
    private final SpaceAuthorizer authorizer;
    private final IdentityAuditService audit;
    private final ReviewCancellationService reviewCancellation;
    private final CategoryMapper categories;
    private final MetadataMapper metadata;
    private final MetadataCommands commands;
    private final MetadataAccess access;

    @Autowired
    public DocumentRelocationService(DocumentRelocationMapper mapper, DocumentAclMapper acls,
                                     SpaceMapper spaces, SpaceAuthorizer authorizer,
                                      IdentityAuditService audit, ReviewCancellationService reviewCancellation,
                                      CategoryMapper categories, MetadataMapper metadata, MetadataCommands commands,
                                      MetadataAccess access) {
        this.mapper = mapper;
        this.acls = acls;
        this.spaces = spaces;
        this.authorizer = authorizer;
        this.audit = audit;
        this.reviewCancellation = reviewCancellation;
        this.categories = categories;
        this.metadata = metadata;
        this.commands = commands;
        this.access = access;
    }

    /**
     * 把文档迁入目标空间。
     *
     * <p>判定顺序是有意的，不能重排：
     * <ol>
     *   <li>请求自身的错误先拒（夹带密级、缺请求标识、源目标相同）——它们不需要读库。</li>
     *   <li>绑定请求，预读实际空间，按空间 ID 升序锁树状态，再锁文档；等待后重新鉴权。</li>
     *   <li>对实际归属与目标空间各判定一次 manage。<b>先鉴权再暴露状态</b>：
     *       否则一个什么都不管的人可以靠 409 与 403 的差异探出"某文档是否在某空间里"。</li>
     *   <li>幂等重放判定<b>必须排在状态校验之前</b>：首次搬迁成功后文档已经在目标空间，
     *       重投时第 5 步的"目标等于当前归属"会命中，把一次正当的重放变成 409。</li>
     *   <li>状态校验：目标等于当前归属、声明归属与实际不符、目标已归档。</li>
     *   <li>写入：取消审核、清空例外、原子切换空间/分类并推进资料版本、同步分块、保存回执和审计。</li>
     * </ol>
     *
     * @param documentId             被搬迁的文档
     * @param fromSpaceId            调用方声明的当前所属空间；与实际不符即 409，不以实际值替代它
     * @param toSpaceId              目标空间
     * @param requestedSecurityLevel 请求夹带的密级；只为能<b>显式拒绝</b>而接收，非 null 即 400
     * @param actorUserId            审计记录的操作者；为 null 时取服务端解析出的会话主体
     * @param requestId              请求幂等标识，不可为空
     * @param targetCategoryId       目标空间分类，null 表示明确未分类
     * @param expectedMetadataVersion 预期的独立资料版本
     * @return 搬迁回执；重放时为首次搬迁的结果且 {@code replayed} 为真
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentRelocationResult relocate(long documentId, long fromSpaceId, long toSpaceId,
                                             Short requestedSecurityLevel, Long actorUserId,
                                             String requestId, Long targetCategoryId, long expectedMetadataVersion) {
        requireNoSecurityLevelChange(requestedSecurityLevel);
        String request = requireRequestId(requestId);
        if (documentId <= 0 || fromSpaceId <= 0 || toSpaceId <= 0 || expectedMetadataVersion < 0
                || (targetCategoryId != null && targetCategoryId <= 0)) {
            throw IdentityException.badRequest("请求参数无效");
        }
        if (fromSpaceId == toSpaceId) {
            throw IdentityException.badRequest("源空间与目标空间相同，搬迁没有意义");
        }
        long initialActor = access.current().userId();
        if (actorUserId != null && actorUserId != initialActor) {
            throw IdentityException.forbidden("操作者不匹配");
        }
        var binding = commands.bind(AuditScope.DOCUMENT_RELOCATE, request, initialActor, documentId, null, documentId,
                expectedMetadataVersion, new RelocationInput(fromSpaceId, toSpaceId, targetCategoryId, expectedMetadataVersion));
        var previous = commands.lock(binding);
        var hint = metadata.document(documentId);
        if (hint == null || "deleted".equals(hint.getStatus())) {
            throw IdentityException.notFound("文档不存在或已删除");
        }
        for (long space : LongStream.of(hint.getSpaceId(), toSpaceId).distinct().sorted().toArray()) {
            if (categories.shareTree(space) == null) {
                throw IdentityException.notFound("空间不存在或已删除");
            }
        }
        long owning = requireOwningSpaceLocked(documentId);
        long currentActor = access.current().userId();
        if (currentActor != initialActor) {
            throw IdentityException.forbidden("操作者已变化");
        }
        // 鉴权针对实际归属，不是请求声明的 fromSpaceId——否则调用方可以指定一个
        // 自己管得动的空间，来搬走一篇根本不在那里的文档。
        long actor = requireRelocationManager(owning, "源空间");
        SpaceRecord target = requireTargetSpace(toSpaceId);
        requireRelocationManager(toSpaceId, "目标空间");
        if (previous != null) {
            requireRelocationManager(fromSpaceId, "原源空间");
            var result = commands.receipt(previous, DocumentRelocationResult.class);
            return new DocumentRelocationResult(result.documentId(), result.fromSpaceId(), result.toSpaceId(),
                    result.clearedExceptionCount(), result.clearedExceptions(), result.clearedExceptionsTruncated(),
                    result.clearanceBlockedPrincipalCount(), true, result.targetCategoryId(), result.metadataVersion());
        }
        if (audit.existing(AuditScope.DOCUMENT_RELOCATE, request) != null) {
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "旧请求标识没有新协议绑定");
        }

        if (owning == toSpaceId) {
            throw IdentityException.conflict(IdentityConflictReason.ALREADY_IN_TARGET,
                    "文档已在目标空间 " + toSpaceId + " 内，无需搬迁");
        }
        if (owning != fromSpaceId || owning != hint.getSpaceId()) {
            throw IdentityException.conflict(IdentityConflictReason.SOURCE_MISMATCH,
                    "文档当前不在声明的源空间 " + fromSpaceId + " 内，可能已被他人搬走；"
                            + "请重新读取归属后再决定是否搬迁");
        }
        if (Boolean.TRUE.equals(target.getArchived())) {
            // 迁出已归档空间是正当的抢救动作，所以只拦迁入：迁入会让文档对所有人
            // 立即消失，用搬迁接口做隐藏是误导性的路径。
            throw IdentityException.conflict(IdentityConflictReason.TARGET_ARCHIVED,
                    "不能迁入已归档空间 " + toSpaceId + "，文档会对所有人立即消失；请先取消归档");
        }

        var beforeMetadata = metadata.document(documentId);
        if (beforeMetadata.getMetadataVersion() != expectedMetadataVersion) {
            throw IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, "文档资料已变化");
        }
        if (targetCategoryId != null && metadata.category(toSpaceId, targetCategoryId) == null) {
            throw IdentityException.conflict(IdentityErrorCode.INVALID_PARENT, "目标分类不可选");
        }

        reviewCancellation.cancelDocument(documentId, actor);
        // 预读只为出回执摘要；删除按 doc_id 整体执行，范围不取自这次预读。
        List<DocumentAclEntry> preview = mapper.listAclEntries(documentId);
        boolean truncated = preview.size() > CLEARED_SUMMARY_LIMIT;
        List<String> summary = preview.stream().limit(CLEARED_SUMMARY_LIMIT)
                .map(DocumentAclEntry::summary).toList();
        int cleared = mapper.clearAcl(documentId);

        if (mapper.updateDocumentPlacement(documentId, toSpaceId, targetCategoryId, expectedMetadataVersion) != 1) {
            // 乐观版本和状态条件作为最终写入边界。
            throw IdentityException.conflict(IdentityErrorCode.CONFLICT, "文档已被其他操作变更");
        }
        int syncedChunks = mapper.syncChunkSpace(documentId, toSpaceId);
        long blocked = countClearanceBlocked(documentId, toSpaceId);

        long nextVersion = Math.addExact(expectedMetadataVersion, 1);
        var receipt = new DocumentRelocationResult(documentId, owning, toSpaceId, cleared, summary,
                truncated, blocked, false, targetCategoryId, nextVersion);
        commands.save(binding, receipt);
        audit.appendSuccess(AuditScope.DOCUMENT_RELOCATE, request, actor, documentId, "relocate",
                commands.write(new RelocationBefore(owning, beforeMetadata.getCategoryId(), expectedMetadataVersion, summary, truncated)),
                commands.write(new RelocationAfter(toSpaceId, targetCategoryId, nextVersion, cleared, blocked)));
        log.info("文档搬迁完成: documentId={} from={} to={} clearedExceptions={} chunks={} blocked={}",
                documentId, owning, toSpaceId, cleared, syncedChunks, blocked);

        return receipt;
    }

    private record RelocationInput(long fromSpaceId, long toSpaceId, Long targetCategoryId, long expectedMetadataVersion) {
    }

    private record RelocationBefore(long spaceId, Long categoryId, long metadataVersion,
                                    List<String> clearedExceptions, boolean clearedExceptionsTruncated) {
    }

    private record RelocationAfter(long spaceId, Long categoryId, long metadataVersion,
                                   int clearedExceptionCount, long clearanceBlockedPrincipalCount) {
    }

    /**
     * 取实际归属并锁住文档行。
     *
     * <p>这把锁是搬迁与 {@code ChunkPublisher} 发布事务的串行化点；两侧都取同一把锁，
     * 缺任何一侧都不构成串行化。
     */
    private long requireOwningSpaceLocked(long documentId) {
        Long spaceId = mapper.findOwningSpaceIdForUpdate(documentId);
        if (spaceId == null) {
            throw IdentityException.notFound("文档不存在或已删除");
        }
        return spaceId;
    }

    /**
     * 目标空间必须存在。空间删除是硬删除，所以"查不到"同时覆盖不存在与已删除。
     */
    private SpaceRecord requireTargetSpace(long toSpaceId) {
        SpaceRecord target = spaces.findRawById(toSpaceId);
        if (target == null) {
            throw IdentityException.notFound("目标空间不存在或已删除");
        }
        return target;
    }

    /**
     * 要求操作者具备某一侧空间的管理资格，并在拒绝时说明搬迁为何需要 manage。
     *
     * <p>只改写 {@code FORBIDDEN}：停用主体抛出的是 {@code DISABLED}，它的含义与
     * "资格不足"不同，原样向上传播。
     *
     * <p>两侧都要 manage 而不是 edit：搬迁把文档从源空间整个授权名单里撤出、放进目标
     * 空间的整个授权名单，这是对两个空间的授权变更。只要 edit 就允许搬迁，等于让源
     * 空间的普通编辑者把内容推送给目标空间的全部受众。
     */
    private long requireRelocationManager(long spaceId, String side) {
        try {
            return authorizer.requireSpaceManager(spaceId);
        } catch (IdentityException denied) {
            if (denied.errorCode() != IdentityErrorCode.FORBIDDEN) {
                throw denied;
            }
            throw IdentityException.forbidden("搬迁文档需要" + side + "（" + spaceId
                    + "）的 manage 管理资格；edit 不足以把文档推送给另一个空间的全部受众");
        }
    }

    /**
     * 统计目标空间里因密级而当下看不到这篇文档的主体数。
     *
     * <p>口径与 {@code DocumentAclService.countClearanceBlocked} 完全一致：主体展开为
     * 启用成员用户，取其中<b>最高</b>密级，该最高密级低于文档密级才计入受阻。因此
     * 混合密级的部门<b>不计入</b>——只要有成员够格，这条授权对该部门就是生效的。
     *
     * <p>两处各算一遍而不抽成公共方法，沿用 {@code SpaceAclService} 与
     * {@code DocumentAclService} 之间已有的做法；但口径必须跟着改：两个接口回执同名
     * 字段却算不同的东西，比没有回执更糟。
     */
    private long countClearanceBlocked(long documentId, long spaceId) {
        long blocked = 0;
        for (SpaceViewPrincipal principal : mapper.listViewPrincipals(spaceId)) {
            List<Long> userIds = userIdsForPrincipal(principal);
            if (userIds.isEmpty()) {
                continue;
            }
            short best = 0;
            for (Long userId : userIds) {
                Short clearance = acls.findUserClearance(userId);
                if (clearance != null && clearance > best) {
                    best = clearance;
                }
            }
            if (acls.countDocumentAboveClearance(documentId, best) > 0) {
                blocked++;
            }
        }
        return blocked;
    }

    /**
     * 主体展开：只为回执统计密级，因此按主体类型直接查用户。
     */
    private List<Long> userIdsForPrincipal(SpaceViewPrincipal principal) {
        if (principal.principalType() == PrincipalType.USER.code()) {
            return acls.findUserClearance(principal.principalId()) == null
                    ? List.of() : List.of(principal.principalId());
        }
        return acls.listAclUserIds(principal.principalType(), principal.principalId());
    }

    /**
     * 搬迁不改密级，夹带即拒。
     *
     * <p>静默忽略这个字段会让调用方以为密级已经改了。密级调整必须是独立的、受全局
     * 管理员保护的操作，不能借任何一个"顺手的"接口完成。
     */
    private static void requireNoSecurityLevelChange(Short requestedSecurityLevel) {
        if (requestedSecurityLevel != null) {
            throw IdentityException.badRequest(
                    "搬迁不修改文档密级；密级调整是独立的、受全局管理员保护的操作，"
                            + "不能夹带在搬迁请求里");
        }
    }

    /**
     * 请求标识在本操作里不是可选项。
     *
     * <p>别处缺它只是丢掉幂等；这里缺它意味着一次超时重投会第二次清空例外，
     * 把目标空间管理员刚授出的授权一并清掉。所以服务层自己也拦一道，
     * 不只依赖控制器。
     */
    private static String requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw IdentityException.badRequest("缺少请求标识 requestId");
        }
        return MetadataInput.requestId(requestId);
    }
}
