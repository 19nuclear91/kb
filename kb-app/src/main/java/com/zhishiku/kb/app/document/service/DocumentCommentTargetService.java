package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentCommentTargetMapper;
import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 评论模块读取固定版本资格的唯一入口：每次按当前主体分别解析 view 与 comment 范围，
 * 不缓存页面快照。评论模块只经本服务取得文档事实，不访问 document mapper。
 */
@Service
public class DocumentCommentTargetService {
    private final DocumentCommentTargetMapper mapper;
    private final PermissionService permissions;

    public DocumentCommentTargetService(DocumentCommentTargetMapper mapper, PermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }

    /** @return 文档或版本不存在、归属不符时为空；依赖故障向上抛出，不转换为不可见 */
    public DocumentCommentTarget inspect(PermissionService.PrincipalHandle handle, long documentId, long versionId) {
        return query(handle, documentId, versionId, false);
    }

    /** 写入事务内锁定文档后重读实际空间、密级与授权，不沿用锁外判断。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentCommentTarget lock(PermissionService.PrincipalHandle handle, long documentId, long versionId) {
        return query(handle, documentId, versionId, true);
    }

    private DocumentCommentTarget query(PermissionService.PrincipalHandle handle, long documentId, long versionId,
                                        boolean locked) {
        var view = permissions.resolve(handle, PermissionService.PERM_VIEW);
        var comment = permissions.resolve(handle, PermissionService.PERM_COMMENT);
        List<String> keys = List.of(view.principalKeys());
        var target = locked
                ? mapper.lockFacts(documentId, versionId, view.visibleSpaceIdList(), comment.visibleSpaceIdList(), keys,
                view.clearanceLevel())
                : mapper.facts(documentId, versionId, view.visibleSpaceIdList(), comment.visibleSpaceIdList(), keys,
                view.clearanceLevel());
        if (target != null && (target.documentId() != documentId || target.versionId() != versionId)) {
            throw new IllegalStateException("Comment target is inconsistent");
        }
        return target;
    }
}
