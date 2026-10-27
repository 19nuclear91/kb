package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.space.mapper.UploadSpaceMapper;
import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 普通用户上传空间查询，管理资格不替代空间 edit。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class UploadSpaceQueryService {
    private static final String CURSOR_KIND = "upload-spaces";
    private final UploadSpaceMapper mapper;
    private final AdminQueryAccess access;
    private final PermissionService permissions;

    public UploadSpaceQueryService(UploadSpaceMapper mapper, AdminQueryAccess access, PermissionService permissions) {
        this.mapper = mapper;
        this.access = access;
        this.permissions = permissions;
    }

    /**
     * 每页重新取得当前身份和空间编辑范围。
     *
     * @param input 原始多值查询参数
     * @return 不含全局总数的候选页
     */
    public QueryPage<UploadSpaceCandidate> list(Map<String, List<String>> input) {
        try {
            var handle = access.current();
            handle.requireEnabled();
            var params = new QueryParameters(input, "limit", "cursor");
            int limit = params.limit();
            var cursor = QueryCursor.decode(params.cursor(), CURSOR_KIND);
            Map<String, Object> filters = Map.of();
            if (cursor != null) cursor.verify(CURSOR_KIND, filters);
            var spaces = permissions.resolve(handle, PermissionService.PERM_EDIT).visibleSpaceIdList();
            if (spaces.isEmpty()) return new QueryPage<>(List.of(), false, null);
            var rows = mapper.list(spaces, cursor == null ? null : cursor.id(), limit + 1);
            return QueryPage.slice(rows, limit, row -> QueryCursor.encode(CURSOR_KIND, filters, row.spaceId()));
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw new QueryException(503, "查询服务暂不可用");
        }
    }
}
