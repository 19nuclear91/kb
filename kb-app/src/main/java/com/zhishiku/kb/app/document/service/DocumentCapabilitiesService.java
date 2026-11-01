package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.model.DocumentCapabilities;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 按实际文档授权谓词分别核对查看、下载和编辑，不读取文件或改变授权。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class DocumentCapabilitiesService {
    private final DocumentMapper mapper;
    private final AdminQueryAccess access;
    private final PermissionService permissions;

    public DocumentCapabilitiesService(DocumentMapper mapper, AdminQueryAccess access, PermissionService permissions) {
        this.mapper = mapper;
        this.access = access;
        this.permissions = permissions;
    }

    /**
     * 只有当次可查看的父文档才提供最小操作事实。
     *
     * @param id 原始文档路径标识
     * @param input 原始参数，必须为空
     * @return 独立操作资格
     */
    public DocumentCapabilities get(String id, Map<String, List<String>> input) {
        try {
            var handle = access.current();
            handle.requireEnabled();
            new QueryParameters(input);
            long documentId = QueryParameters.positiveValue(id);
            var view = permissions.resolve(handle, PermissionService.PERM_VIEW);
            if (!matches(documentId, view, PermissionService.PERM_VIEW)) {
                throw QueryException.missing();
            }
            boolean download = matches(documentId, permissions.resolve(handle, PermissionService.PERM_DOWNLOAD),
                    PermissionService.PERM_DOWNLOAD);
            boolean edit = matches(documentId, permissions.resolve(handle, PermissionService.PERM_EDIT),
                    PermissionService.PERM_EDIT);
            return new DocumentCapabilities(documentId, download, edit, edit, edit, edit);
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw new QueryException(503, "查询服务暂不可用");
        }
    }

    private boolean matches(long documentId, PermissionScope scope, String permission) {
        return !scope.seesNothing() && mapper.findSummary(documentId, scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), permission) != null;
    }
}
