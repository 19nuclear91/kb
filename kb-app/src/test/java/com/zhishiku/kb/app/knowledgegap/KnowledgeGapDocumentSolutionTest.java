package com.zhishiku.kb.app.knowledgegap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.KnowledgeGapParticipantAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.model.PublishedDocumentReference;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationEvidence;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 文档型方案使用双方当前可读固定版本，撤权不降级为文字型，也不替换最新版。 */
class KnowledgeGapDocumentSolutionTest {
    private final KnowledgeGapMemoryFixture fixture = new KnowledgeGapMemoryFixture();

    @Test
    void submitNeedsBothCurrentReadersAndPersistsOnlyFixedDocumentIdentity() {
        fixture.readable.put(10L, false);
        assertStatus(409, () -> fixture.solutions.submit(11, input(), "unreadable"));
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(fixture.entries).isEmpty();
        fixture.readable.put(10L, true);
        var solution = fixture.solutions.submit(11, input(), "valid");
        var entry = fixture.entries.get(solution.solutionId());
        assertThat(entry.documentId()).isEqualTo(21);
        assertThat(entry.versionId()).isEqualTo(31);
        assertThat(entry.body()).isEqualTo("人工处理说明");
        assertThat(entry.toString()).doesNotContain("授权标题");
    }

    @Test
    void reporterInactivityPreventsDocumentSubmissionButDoesNotRewriteState() {
        doThrow(ApiException.conflict("提问者当前不具备资料确认资格")).when(fixture.participants).active(10);
        assertStatus(409, () -> fixture.solutions.submit(11, input(), "disabled"));
        assertThat(fixture.gap.pendingSolutionId()).isNull();
        assertThat(fixture.entries).isEmpty();
        verifyNoInteractions(fixture.documents);
    }

    @Test
    void confirmingAfterRevocationFailsButExplicitRejectionRemainsAvailable() {
        var solution = fixture.solutions.submit(11, input(), "submit");
        fixture.actorId = 10;
        fixture.readable.put(10L, false);
        assertStatus(409, () -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "confirm"));
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.AWAITING_CONFIRMATION);
        fixture.solutions.reject(11, new KnowledgeGapInputs.Rejection(2, solution.solutionId(), "当前资料不可读"), "reject");
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(fixture.entries.get(solution.solutionId()).versionId()).isEqualTo(31);
    }

    @Test
    void candidateCursorIsScopedAndOnlyExposesAuthorizedFixedIdentityAndTitle() throws Exception {
        var actor = fixture.access.current();
        when(fixture.documents.candidates(any(), eq("资料"), eq(null), eq(2))).thenReturn(List.of(
                new PublishedDocumentReference(21, 31, "可读资料", PublicationEvidence.PUBLISHED),
                new PublishedDocumentReference(22, 32, "可读资料2", PublicationEvidence.PUBLISHED)));
        var page = fixture.queries.candidates(actor, 11, new KnowledgeGapInputs.Search("资料", 1, null));
        assertThat(page.hasMore()).isTrue();
        var json = new ObjectMapper().valueToTree(page.items().getFirst());
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("documentId")).isTrue();
        assertThat(json.has("versionId")).isTrue();
        assertThat(json.has("title")).isTrue();
        String wrong = QueryCursor.encode("knowledge-gap-solution-documents",
                QueryParameters.filters("actor", 8, "gapId", 12, "nameQuery", "资料"), 21);
        assertThatThrownBy(() -> fixture.queries.candidates(actor, 11, new KnowledgeGapInputs.Search("资料", 1, wrong)))
                .isInstanceOf(QueryException.class);
    }

    @Test
    void participantPasswordStateIsRequiredAndDirectoryFailuresStayFailures() {
        var principals = mock(PermissionMapper.class);
        var identities = mock(IdentityDepartmentMapper.class);
        when(principals.findClearanceLevel(10)).thenReturn((short) 1);
        var participants = new KnowledgeGapParticipantAccess(new PermissionService(principals, null, mock(RolePermissionMapper.class)), identities);
        when(identities.findMustChangePasswordById(10)).thenReturn(false);
        assertThat(participants.active(10).userId()).isEqualTo(10);
        when(identities.findMustChangePasswordById(10)).thenReturn(true);
        assertStatus(409, () -> participants.active(10));
        when(identities.findMustChangePasswordById(10)).thenReturn(null);
        assertStatus(409, () -> participants.active(10));
        when(identities.findMustChangePasswordById(10)).thenThrow(new DataAccessResourceFailureException("secret identity sql"));
        assertThatThrownBy(() -> participants.active(10)).hasMessage("暂时无法确认提问者资格");
    }

    private static KnowledgeGapInputs.Solution input() {
        return new KnowledgeGapInputs.Solution(1, "人工处理说明", 21L, 31L);
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
