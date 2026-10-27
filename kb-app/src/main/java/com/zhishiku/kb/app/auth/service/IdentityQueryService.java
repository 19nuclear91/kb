package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityQueryMapper;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.ExternalBinding;
import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.security.IdentitySummary;
import com.zhishiku.kb.common.security.PrincipalType;
import com.zhishiku.kb.common.security.RoleSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 身份读取每次先鉴权，且不使用会过滤停用目标的主体解析代替绑定查询。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class IdentityQueryService {
    private final IdentityQueryMapper mapper;
    private final AdminQueryAccess access;

    public IdentityQueryService(IdentityQueryMapper mapper, AdminQueryAccess access) {
        this.mapper = mapper;
        this.access = access;
    }

    public QueryPage<IdentitySummary> users(Map<String, List<String>> input) {
        access.require(Permission.IDENTITY_USER_READ);
        var p = new QueryParameters(input, "q", "departmentId", "enabled", "limit", "cursor");
        String q = p.text("q", 128);
        Long department = p.positive("departmentId");
        Boolean enabled = p.bool("enabled");
        var filters = QueryParameters.filters("q", q, "departmentId", department, "enabled", enabled);
        var cursor = cursor(p, "users", filters);
        int limit = p.limit();
        return QueryPage.slice(mapper.users(QueryParameters.like(q), department, enabled,
                        cursor == null ? null : cursor.id(), limit + 1), limit,
                row -> QueryCursor.encode("users", filters, row.userId()));
    }

    public QueryPage<RoleSummary> roles(String rawUserId, Map<String, List<String>> input) {
        access.require(Permission.IDENTITY_USER_READ);
        long userId = QueryParameters.positiveValue(rawUserId);
        var p = new QueryParameters(input, "limit", "cursor");
        var filters = QueryParameters.filters("userId", userId);
        var cursor = cursor(p, "user-roles", filters);
        int limit = p.limit();
        requireUser(userId);
        return QueryPage.slice(mapper.roles(userId, cursor == null ? null : cursor.id(), limit + 1), limit,
                row -> QueryCursor.encode("user-roles", filters, row.roleId()));
    }

    public QueryPage<ExternalBinding> external(String rawUserId, Map<String, List<String>> input) {
        access.require(Permission.IDENTITY_EXTERNAL_READ);
        long userId = QueryParameters.positiveValue(rawUserId);
        var p = new QueryParameters(input, "provider", "limit", "cursor");
        String provider = p.exactText("provider", 128);
        var filters = QueryParameters.filters("userId", userId, "provider", provider);
        var cursor = cursor(p, "user-external", filters);
        int limit = p.limit();
        requireUser(userId);
        return QueryPage.slice(mapper.external(userId, provider, cursor == null ? null : cursor.id(), limit + 1), limit,
                row -> QueryCursor.encode("user-external", filters, row.identityId()));
    }

    /**
     * 调用方必须先取得具体资源管理资格；此服务只返回最小候选。
     */
    public QueryPage<PrincipalOption> principals(String kind, long resourceId, QueryParameters p) {
        String typeValue = p.exactText("principalType", 4);
        PrincipalType type;
        try {
            type = PrincipalType.valueOf(typeValue == null ? "" : typeValue);
        } catch (IllegalArgumentException ignored) {
            throw QueryException.invalid();
        }
        String q = p.text("q", 128);
        var filters = QueryParameters.filters("resourceId", resourceId, "type", type, "q", q);
        var cursor = cursor(p, kind, filters);
        int limit = p.limit();
        return QueryPage.slice(mapper.principals(type, QueryParameters.like(q), cursor == null ? null : cursor.id(),
                        limit + 1),
                limit, row -> QueryCursor.encode(kind, filters, row.principalId()));
    }

    /**
     * 调用方必须已按具体资源授权当前读取；只返回请求 ID 的显示标签，不提供模糊检索或身份关系。
     *
     * @return 按用户 ID 索引的标签；查询结果混入未请求 ID 时视为依赖故障
     */
    public Map<Long, String> labels(Collection<Long> userIds) {
        if (userIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Invalid label batch");
        }
        var ids = userIds.stream().distinct().sorted().toList();
        if (ids.size() > 200) {
            throw new IllegalArgumentException("Invalid label batch");
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> labels = new HashMap<>();
        for (var row : mapper.labels(ids)) {
            if (!ids.contains(row.userId()) || row.label() == null || labels.put(row.userId(), row.label()) != null) {
                throw new IllegalStateException("User labels are inconsistent");
            }
        }
        return Map.copyOf(labels);
    }

    private void requireUser(long id) {
        if (!mapper.userExists(id)) {
            throw QueryException.missing();
        }
    }

    private static QueryCursor cursor(QueryParameters p, String kind, Map<String, Object> filters) {
        QueryCursor cursor = QueryCursor.decode(p.cursor(), kind);
        if (cursor != null) {
            cursor.verify(kind, filters);
        }
        return cursor;
    }
}
