package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.service.PublishedDocumentQueryService;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapDocumentOption;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryPage;
import com.zhishiku.kb.app.query.QueryParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只检查处理人自身资料范围；提交方案时才独立复核提问者，不暴露其 ACL。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class KnowledgeGapSolutionQueryService {
    private static final String CURSOR_KIND = "knowledge-gap-solution-documents";
    private final KnowledgeGapAccess access;
    private final PublishedDocumentQueryService documents;

    public KnowledgeGapSolutionQueryService(KnowledgeGapAccess access, PublishedDocumentQueryService documents) {
        this.access = access;
        this.documents = documents;
    }

    public QueryPage<KnowledgeGapDocumentOption> candidates(PermissionService.PrincipalHandle actor, long gapId,
                                                            KnowledgeGapInputs.Search input) {
        access.requireForAction(actor, gapId, KnowledgeGapAction.SOLUTION);
        var filters = QueryParameters.filters("actor", actor.userId(), "gapId", gapId, "nameQuery", input.nameQuery());
        var cursor = QueryCursor.decode(input.cursor(), CURSOR_KIND);
        if (cursor != null) {
            cursor.verify(CURSOR_KIND, filters);
        }
        var rows = documents.candidates(actor, input.nameQuery(), cursor == null ? null : cursor.id(), input.limit() + 1);
        var options = rows.stream().map(row -> new KnowledgeGapDocumentOption(row.documentId(), row.versionId(), row.title())).toList();
        return QueryPage.slice(options, input.limit(), row -> QueryCursor.encode(CURSOR_KIND, filters, row.documentId()));
    }
}
