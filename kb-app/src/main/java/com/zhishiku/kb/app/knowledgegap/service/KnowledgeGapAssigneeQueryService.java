package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.model.KnowledgeGapAssigneeViews;
import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 具体问题的候选范围；不直连 auth Mapper，不提供身份目录回退。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class KnowledgeGapAssigneeQueryService {
    private static final String CURSOR_KIND = "knowledge-gap-assignees";
    private final KnowledgeGapAccess access;
    private final KnowledgeGapAssigneeService assignees;

    public KnowledgeGapAssigneeQueryService(KnowledgeGapAccess access, KnowledgeGapAssigneeService assignees) {
        this.access = access;
        this.assignees = assignees;
    }

    public QueryPage<KnowledgeGapAssigneeViews.Candidate> search(PermissionService.PrincipalHandle actor, long gapId,
                                                                KnowledgeGapInputs.Search input) {
        access.requireForAction(actor, gapId, KnowledgeGapAction.ASSIGN);
        var filters = QueryParameters.filters("actor", actor.userId(), "gapId", gapId, "nameQuery", input.nameQuery());
        var cursor = QueryCursor.decode(input.cursor(), CURSOR_KIND);
        if (cursor != null) {
            cursor.verify(CURSOR_KIND, filters);
        }
        var rows = assignees.candidates(actor, input.nameQuery(), cursor == null ? null : cursor.id(), input.limit() + 1);
        return QueryPage.slice(rows, input.limit(), row -> QueryCursor.encode(CURSOR_KIND, filters, row.userId()));
    }
}
