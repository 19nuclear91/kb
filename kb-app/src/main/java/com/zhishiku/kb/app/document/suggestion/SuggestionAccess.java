package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.service.MetadataAccess;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import com.zhishiku.kb.common.security.IdentityException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Component;

@Component
public class SuggestionAccess {
    public record Context(long actorId, long authEpoch, long departmentId, long docId, long spaceId,
                          Long versionId, long metadataVersion, long treeVersion) {
    }

    private final EpochSessionService sessions;
    private final PermissionService permissions;
    private final AuthService auth;
    private final MetadataAccess access;
    private final MetadataMapper metadata;
    private final CategoryMapper categories;
    private final DocumentVersionAccessService versions;
    private final SqlSessionTemplate sqlSession;

    public SuggestionAccess(EpochSessionService sessions, PermissionService permissions, AuthService auth,
                            MetadataAccess access, MetadataMapper metadata, CategoryMapper categories,
                            DocumentVersionAccessService versions, SqlSessionTemplate sqlSession) {
        this.sessions = sessions;
        this.permissions = permissions;
        this.auth = auth;
        this.access = access;
        this.metadata = metadata;
        this.categories = categories;
        this.versions = versions;
        this.sqlSession = sqlSession;
    }

    public Context current(String token, long docId) {
        // 行锁或幂等插入可能等待其他事务提交，重新鉴权时读取最新的资料与目录。
        sqlSession.clearCache();
        var session = sessions.check(token);
        if (session.mustChangePassword()) {
            throw IdentityException.passwordChangeRequired("请先修改密码");
        }
        var actor = permissions.principalOf(session.userId());
        actor.requireEnabled();
        var editable = access.document(actor, docId, true);
        var document = metadata.document(docId);
        if (document == null || !document.getSpaceId().equals(editable.document().getSpaceId())) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
        Long tree = categories.version(document.getSpaceId());
        if (tree == null) {
            throw new MetadataException(MetadataException.Reason.UNAVAILABLE);
        }
        if (document.getCurrentVersionId() != null) {
            requireSource(actor.userId(), docId, document.getCurrentVersionId());
        }
        var principal = auth.quotaPrincipal(actor.userId());
        return new Context(actor.userId(), session.authEpoch(), principal.departmentId(), docId,
                document.getSpaceId(), document.getCurrentVersionId(), document.getMetadataVersion(), tree);
    }

    public void lockForPermit(SuggestionJob job) {
        Long tree = categories.shareTree(job.spaceId());
        if (tree == null || tree != job.treeVersion()) {
            throw new MetadataException(MetadataException.Reason.TREE_VERSION_CONFLICT);
        }
        if (metadata.lockDocument(job.docId()) == null) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
    }

    public void requireModelSource(SuggestionJob job) {
        versions.require(job.actorId(), job.docId(), job.sourceVersionId(), VersionAccessPurpose.MODEL_SOURCE);
    }

    public void requireSource(long actor, long doc, long version) {
        versions.require(actor, doc, version, VersionAccessPurpose.INTERACTIVE);
    }
}
