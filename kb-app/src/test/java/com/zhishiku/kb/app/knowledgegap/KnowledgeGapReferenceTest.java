package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapViews;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapReferenceService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

/** 当前和历史资料投影不延续文档资格，不加载正文或文件。 */
class KnowledgeGapReferenceTest {
    private final KnowledgeGapMemoryFixture fixture = new KnowledgeGapMemoryFixture();
    private final KnowledgeGapReferenceService service = new KnowledgeGapReferenceService(fixture.access, fixture.documents);

    @Test
    void operatorReadDoesNotRevealHiddenReferenceIdentityTitleOrCount() {
        fixture.actorId = 7;
        var view = service.project(fixture.access.current(), fixture.gap, entry(21L, 31L), "处理人");
        assertThat(view.text()).isEqualTo("主动分享说明");
        assertThat(view.referenceState()).isEqualTo(KnowledgeGapViews.ReferenceState.UNAVAILABLE);
        assertThat(view.reference()).isNull();
        var json = JsonMapper.builder().findAndAddModules().build().valueToTree(view);
        assertThat(json.toString()).doesNotContain("documentId", "versionId", "title", "count", "授权标题");
    }

    @Test
    void newPublicationDoesNotReplaceOldIdentityAndRevocationClearsProjection() {
        var actor = fixture.access.current();
        var entry = entry(21L, 31L);
        var readable = service.project(actor, fixture.gap, entry, "处理人");
        assertThat(readable.reference().versionId()).isEqualTo(31);
        fixture.readable.put(8L, false);
        var unavailable = service.project(actor, fixture.gap, entry, "处理人");
        assertThat(unavailable.reference()).isNull();
        assertThat(unavailable.referenceState()).isEqualTo(KnowledgeGapViews.ReferenceState.UNAVAILABLE);
        fixture.readable.put(8L, true);
        assertThat(service.project(actor, fixture.gap, entry, "处理人").reference().versionId()).isEqualTo(31);
    }

    @Test
    void resolvedFactSurvivesLaterReferenceRevocation() {
        var solution = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "方案说明", 21L, 31L), "submit");
        fixture.actorId = 10;
        fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "confirm");
        fixture.readable.put(10L, false);
        var view = service.project(fixture.access.current(), fixture.gap, fixture.entries.get(solution.solutionId()), "处理人");
        assertThat(view.referenceState()).isEqualTo(KnowledgeGapViews.ReferenceState.UNAVAILABLE);
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.RESOLVED);
    }

    @Test
    void sourceFailureOrUnexpectedVersionDoesNotReturnOldSnapshot() {
        doThrow(ApiException.unavailable("secret title")).when(fixture.documents).reference(any(), anyLong(), anyLong());
        assertThatThrownBy(() -> service.project(fixture.access.current(), fixture.gap, entry(21L, 31L), "处理人"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503));
        doReturn(Optional.of(new PublishedDocumentReference(21, 32, "新版标题", PublicationEvidence.PUBLISHED)))
                .when(fixture.documents).reference(any(), anyLong(), anyLong());
        assertThatThrownBy(() -> service.project(fixture.access.current(), fixture.gap, entry(21L, 31L), "处理人"))
                .hasMessage("知识问题单服务暂不可用");
    }

    @Test
    void explanationDoesNotQueryDocumentsAndCrossGapEntryIsRejected() {
        assertThat(service.project(fixture.access.current(), fixture.gap, entry(null, null), "处理人").referenceState())
                .isEqualTo(KnowledgeGapViews.ReferenceState.NONE);
        var crossGap = new KnowledgeGapRows.Entry(UUID.randomUUID(), 12, 2, "SOLUTION", 8, "主动分享说明", 21L, 31L, Instant.EPOCH);
        assertThatThrownBy(() -> service.project(fixture.access.current(), fixture.gap, crossGap, "处理人"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(fixture.documents);
    }

    private static KnowledgeGapRows.Entry entry(Long documentId, Long versionId) {
        return new KnowledgeGapRows.Entry(UUID.randomUUID(), 11, 2, "SOLUTION", 8, "主动分享说明", documentId, versionId, Instant.EPOCH);
    }
}
