package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.service.PublishedDocumentQueryService;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapDocumentOption;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 调用方先按当次问题查询授权；每个方案独立核对查看者原固定资料，绝不返回持久化资料身份。 */
@Service
@Transactional(readOnly = true, timeout = 5)
public class KnowledgeGapReferenceService {
    private final KnowledgeGapAccess access;
    private final PublishedDocumentQueryService documents;

    public KnowledgeGapReferenceService(KnowledgeGapAccess access, PublishedDocumentQueryService documents) {
        this.access = access;
        this.documents = documents;
    }

    public KnowledgeGapViews.Entry project(PermissionService.PrincipalHandle actor, KnowledgeGapRows.Gap gap,
                                           KnowledgeGapRows.Entry entry, String authorLabel) {
        return KnowledgeGapFailures.guarded(() -> {
            access.capabilities(actor, gap);
            if (entry == null || entry.gapId() != gap.id() || entry.entryId() == null || entry.position() < 0
                    || entry.authorUserId() <= 0 || entry.body() == null
                    || (entry.documentId() == null) != (entry.versionId() == null)
                    || (entry.documentId() != null && !"SOLUTION".equals(entry.kind()))) {
                throw KnowledgeGapFailures.unavailable();
            }
            var state = KnowledgeGapViews.ReferenceState.NONE;
            KnowledgeGapDocumentOption reference = null;
            if (entry.documentId() != null) {
                state = KnowledgeGapViews.ReferenceState.UNAVAILABLE;
                var readable = documents.reference(actor, entry.documentId(), entry.versionId());
                if (readable.isPresent()) {
                    var row = readable.get();
                    if (row.documentId() != entry.documentId() || row.versionId() != entry.versionId() || !row.published()
                            || row.title() == null) {
                        throw KnowledgeGapFailures.unavailable();
                    }
                    state = KnowledgeGapViews.ReferenceState.AVAILABLE;
                    reference = new KnowledgeGapDocumentOption(row.documentId(), row.versionId(), row.title());
                }
            }
            return new KnowledgeGapViews.Entry(entry.entryId(), entry.position(), entry.kind(),
                    new KnowledgeGapViews.Participant(entry.authorUserId(), authorLabel), entry.body(), state, reference, entry.createdAt());
        });
    }
}
