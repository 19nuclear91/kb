package com.zhishiku.kb.app.document.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.SecurityLevelDictionary;
import com.zhishiku.kb.app.document.mapper.DocumentSecurityLevelMapper;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelChange;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelResult;
import com.zhishiku.kb.app.document.model.DocumentSecurityLevelState;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class DocumentSecurityLevelService {
    private static final AuditScope SCOPE = AuditScope.DOCUMENT_SECURITY_LEVEL_CHANGE;
    private final DocumentSecurityLevelMapper mapper;
    private final IdentityManagementAuthorizer authorizer;
    private final IdentityService identities;
    private final SqlSessionTemplate sessions;
    private final SecurityLevelDictionary levels;
    private final IdentityAuditService audit;
    private final ObjectMapper json;

    public DocumentSecurityLevelService(DocumentSecurityLevelMapper mapper,
                                        IdentityManagementAuthorizer authorizer, IdentityService identities,
                                        SqlSessionTemplate sessions,
                                        SecurityLevelDictionary levels, IdentityAuditService audit, ObjectMapper json) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.identities = identities;
        this.sessions = sessions;
        this.levels = levels;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public DocumentSecurityLevelState get(long documentId) {
        var actor = requireActor();
        requireId(documentId);
        var state = requireDocument(mapper.find(documentId));
        requireClearance(actor, state.securityLevel());
        return state;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentSecurityLevelResult change(long documentId, DocumentSecurityLevelChange request, String header) {
        var initialActor = requireActor();
        requireId(documentId);
        if (request == null) throw IdentityException.badRequest("请求体不能为空");
        var command = request.normalized(header);

        mapper.lockRequest(SCOPE.name() + ":" + command.requestId());
        var actor = requireSameActor(initialActor.userId());
        IdentityAuditRecord previous = audit.existing(SCOPE, command.requestId());
        ChangeSnapshot after = previous == null ? null : matchingSnapshot(previous, actor.userId(), documentId,
                command);

        var before = requireDocument(mapper.lockDocument(documentId));
        actor = requireSameActor(initialActor.userId());
        if (previous == null && !levels.contains(command.securityLevel())) {
            throw IdentityException.badRequest(levels.rejectionMessage("securityLevel", command.securityLevel()));
        }
        requireClearance(actor, before.securityLevel());
        requireClearance(actor, command.securityLevel());
        if (previous != null) {
            var original = read(previous.getBeforeState(), SecuritySnapshot.class);
            requireClearance(actor, original.securityLevel());
            requireClearance(actor, after.securityLevel());
            return result(documentId, original, after, command.requestId(), previous, true);
        }
        if (before.securityVersion() != command.securityVersion()) {
            throw IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, "密级版本已变化，请重新查询后提交");
        }
        if (before.securityLevel() == command.securityLevel()) throw conflict("目标密级与当前密级相同");
        long nextVersion = Math.addExact(before.securityVersion(), 1);
        if (mapper.updateLevel(documentId, command.securityLevel(), command.securityVersion()) != 1) {
            throw conflict("文档状态已变化");
        }
        // V2 的触发器也会同步；仅修正仍不同的分块，避免重复更新索引。
        mapper.syncChunks(documentId, command.securityLevel());
        var original = new SecuritySnapshot(before.securityLevel(), before.securityVersion());
        var changed = new ChangeSnapshot(command.securityLevel(), nextVersion, command.securityVersion());
        var event = audit.successWithReason(SCOPE, command.requestId(), actor.userId(), documentId,
                "change_security_level", write(original), write(changed), command.reason());
        return result(documentId, original, changed, command.requestId(), event, false);
    }

    private ChangeSnapshot matchingSnapshot(IdentityAuditRecord event, long actorId, long documentId,
                                            DocumentSecurityLevelChange command) {
        if (!Objects.equals(event.getActorUserId(), actorId) || !Objects.equals(event.getTargetId(), documentId)
                || !Objects.equals(event.getReason(), command.reason()) || !"SUCCESS".equals(event.getStatus())) {
            throw conflict("请求标识已用于不同操作");
        }
        var after = read(event.getAfterState(), ChangeSnapshot.class);
        if (after.securityLevel() != command.securityLevel()
                || after.expectedSecurityVersion() != command.securityVersion()) {
            throw conflict("请求标识已用于不同操作");
        }
        return after;
    }

    private static DocumentSecurityLevelResult result(long documentId, SecuritySnapshot before,
                                                      ChangeSnapshot after, String requestId,
                                                      IdentityAuditRecord event, boolean replayed) {
        return new DocumentSecurityLevelResult(documentId, before.securityLevel(), after.securityLevel(),
                before.securityVersion(), after.securityVersion(), requestId, event.getCreatedAt(), replayed);
    }

    private ResolvedPrincipal requireSameActor(long expectedId) {
        var actor = requireActor();
        if (actor.userId() != expectedId) throw IdentityException.forbidden("会话主体发生变化");
        return actor;
    }

    private String write(Object state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化密级审计", e);
        }
    }

    private <T> T read(String state, Class<T> type) {
        try {
            return json.readValue(state, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法读取密级审计", e);
        }
    }

    private static IdentityException conflict(String message) {
        return IdentityException.conflict(IdentityErrorCode.CONFLICT, message);
    }

    /**
     * 写入前读取的文档密级与版本快照。
     *
     * @param securityLevel   文档密级
     * @param securityVersion 文档密级的独立乐观锁版本
     */
    private record SecuritySnapshot(short securityLevel, long securityVersion) {
    }

    /**
     * 用于密级写入和幂等校验的变更快照。
     *
     * @param securityLevel           文档密级
     * @param securityVersion         文档密级的独立乐观锁版本
     * @param expectedSecurityVersion 请求期望的密级版本
     */
    private record ChangeSnapshot(short securityLevel, long securityVersion, long expectedSecurityVersion) {
    }

    private ResolvedPrincipal requireActor() {
        // 保留未认证 401；通用管理鉴权器仍负责逐权限匹配。
        StpUtil.checkLogin();
        // 同一事务等待锁后也重新读取身份事实，不复用 MyBatis 一级缓存。
        sessions.clearCache();
        long actor = authorizer.require(Permission.DOCUMENT_SECURITY_LEVEL_WRITE);
        return identities.resolvePrincipal(actor);
    }

    private static void requireId(long documentId) {
        if (documentId <= 0) throw IdentityException.badRequest("documentId 必须为正数");
    }

    private static DocumentSecurityLevelState requireDocument(DocumentSecurityLevelState state) {
        if (state == null) throw IdentityException.notFound("文档不存在或已删除");
        return state;
    }

    private static void requireClearance(ResolvedPrincipal actor, short level) {
        if (level > actor.clearanceLevel()) {
            throw IdentityException.forbidden("文档原密级和目标密级均不得超过操作者当前密级");
        }
    }
}
