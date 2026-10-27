package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.model.IdentityQueryViews.PrincipalOption;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.document.service.DocumentAdminQueryService;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 先按当前资源事实鉴权，再提供跨部门的最小授权候选。
 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class PrincipalQueryService {
    private final AdminQueryAccess access;
    private final SpaceQueryService spaces;
    private final DocumentAdminQueryService documents;
    private final IdentityQueryService identities;

    public PrincipalQueryService(AdminQueryAccess access, SpaceQueryService spaces,
                                 DocumentAdminQueryService documents, IdentityQueryService identities) {
        this.access = access;
        this.spaces = spaces;
        this.documents = documents;
        this.identities = identities;
    }

    public QueryPage<PrincipalOption> space(String rawId, Map<String, List<String>> input) {
        var handle = access.current();
        long id = QueryParameters.positiveValue(rawId);
        spaces.requireManaged(handle, id);
        return identities.principals("space-principals", id, parameters(input));
    }

    public QueryPage<PrincipalOption> document(String rawId, Map<String, List<String>> input) {
        var handle = access.current();
        long id = QueryParameters.positiveValue(rawId);
        documents.managedSpaceOf(handle, id);
        return identities.principals("document-principals", id, parameters(input));
    }

    private static QueryParameters parameters(Map<String, List<String>> input) {
        return new QueryParameters(input, "principalType", "q", "limit", "cursor");
    }
}
