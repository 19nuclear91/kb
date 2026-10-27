package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

/**
 * 批量检查固定版本的当前 view，沿用文档授权谓词。
 */
@Service
public class DocumentVersionAccessService {
    private final DocumentMapper mapper;
    private final PermissionService permissions;

    public DocumentVersionAccessService(DocumentMapper mapper, PermissionService permissions) {
        this.mapper = mapper;
        this.permissions = permissions;
    }

    public Set<SourceKey> visible(PermissionService.PrincipalHandle handle, Set<SourceKey> sources) {
        return visible(handle, sources, VersionAccessPurpose.MODEL_SOURCE);
    }

    public Set<SourceKey> visible(PermissionService.PrincipalHandle handle, Set<SourceKey> sources, VersionAccessPurpose purpose) {
        if (sources.isEmpty()) return Set.of();
        if (purpose == null) throw new IllegalArgumentException("Version access purpose is required");
        try {
            var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
            if (scope.seesNothing()) return Set.of();
            var result = mapper.visibleVersions(new ArrayList<>(sources), scope.visibleSpaceIdList(),
                    Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), purpose);
            if (result == null || !sources.containsAll(result)) throw ApiException.unavailable("暂时无法确认版本访问权限");
            return Set.copyOf(result);
        } catch (Exception error) {
            throw ApiException.unavailable("暂时无法确认历史访问权限");
        }
    }

    public void require(long actor, long documentId, long versionId, VersionAccessPurpose purpose) {
        var source = new SourceKey(documentId, versionId);
        if (!visible(permissions.principalOf(actor), Set.of(source), purpose).contains(source))
            throw ApiException.notFound("文档或版本不存在，或当前不可查看");
    }
}
