package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** 说明型方案、本人确认和过期方案的离线状态闭环，不调用模型、上传或资料服务。 */
class KnowledgeGapResolutionTest {
    private final KnowledgeGapMemoryFixture fixture = new KnowledgeGapMemoryFixture();

    @Test
    void explanationSubmissionWaitsForExplicitReporterConfirmation() throws Exception {
        var submitted = fixture.controller.submit("11", request("submit", "{\"expectedVersion\":1,\"text\":\"处理说明\"}")).data();
        assertThat(submitted.state()).isEqualTo(KnowledgeGapState.AWAITING_CONFIRMATION);
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.AWAITING_CONFIRMATION);
        assertThat(fixture.entries.get(submitted.solutionId()).body()).isEqualTo("处理说明");
        fixture.actorId = 10;
        var confirmed = fixture.controller.confirm("11", request("confirm", "{\"expectedVersion\":2,\"solutionId\":\""
                + submitted.solutionId() + "\"}")).data();
        assertThat(confirmed.state()).isEqualTo(KnowledgeGapState.RESOLVED);
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.RESOLVED);
        verifyNoInteractions(fixture.documents, fixture.participants);
    }

    @Test
    void otherParticipantsCannotSubstituteReporterEvenWithReadAssignOrHandling() {
        var solution = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "说明", null, null), "submit");
        assertStatus(403, () -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "handler"));
        fixture.actorId = 7;
        assertStatus(403, () -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "operator"));
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.AWAITING_CONFIRMATION);
    }

    @Test
    void rejectionPreservesOriginalHandlerAndNextSubmissionGetsNewIdentity() {
        var first = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "第一版说明", null, null), "first");
        fixture.actorId = 10;
        fixture.solutions.reject(11, new KnowledgeGapInputs.Rejection(2, first.solutionId(), "仍然有问题"), "reject");
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(fixture.gap.assigneeUserId()).isEqualTo(8);
        assertThat(fixture.gap.pendingSolutionId()).isNull();
        fixture.actorId = 8;
        var next = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(3, "第二版说明", null, null), "next");
        assertThat(next.solutionId()).isNotEqualTo(first.solutionId());
        assertThat(fixture.entries.get(first.solutionId()).body()).isEqualTo("第一版说明");
        fixture.actorId = 10;
        assertStatus(409, () -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(4, first.solutionId()), "stale"));
    }

    @Test
    void reassigningAwaitingWorkMakesOriginalConfirmationInvalid() {
        var solution = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "说明", null, null), "submit");
        fixture.actorId = 7;
        fixture.assignments.assign(11, new KnowledgeGapInputs.Assignment(2, 9), "reassign");
        fixture.actorId = 10;
        assertStatus(409, () -> fixture.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "late"));
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
    }

    private static MockHttpServletRequest request(String requestId, String body) {
        var request = new MockHttpServletRequest("POST", "/api/knowledge-gaps/11/solutions");
        request.setContentType("application/json");
        request.addHeader("X-Request-Id", requestId);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
