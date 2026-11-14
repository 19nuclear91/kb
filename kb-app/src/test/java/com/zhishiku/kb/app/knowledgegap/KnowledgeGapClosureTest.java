package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.config.KnowledgeGapResponseFilter;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapClosureService;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapClosureController;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 终态、原因与记录保留的离线行为检查，不执行数据库删除或清理。 */
class KnowledgeGapClosureTest {
    private final KnowledgeGapMemoryFixture fixture = new KnowledgeGapMemoryFixture();
    private final KnowledgeGapClosureService closures = new KnowledgeGapClosureService(fixture.access, fixture.mapper, fixture.commands, fixture.facts);
    private final KnowledgeGapClosureController controller = new KnowledgeGapClosureController(fixture.access, closures);

    @Test
    void reporterWithdrawsWithoutErasingSharedDescriptionOrHistory() throws Exception {
        var solution = fixture.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "已分享方案", null, null), "submit");
        fixture.actorId = 10;
        var withdrawn = controller.withdraw("11", request("withdraw", 2, "不再需要")).data();
        assertThat(withdrawn.state()).isEqualTo(KnowledgeGapState.WITHDRAWN);
        assertThat(fixture.gap.description()).isEqualTo("共享问题");
        assertThat(fixture.entries.get(solution.solutionId()).body()).isEqualTo("已分享方案");
        assertThat(fixture.entries).hasSize(2);
        assertThat(closures.withdraw(11, new KnowledgeGapInputs.Message(2, "不再需要"), "withdraw").replayed()).isTrue();
    }

    @Test
    void qualifiedOperatorClosesWithoutReporterActivityOrPretendResolution() {
        fixture.actorId = 7;
        doThrow(ApiException.conflict("提问者当前不具备资料确认资格")).when(fixture.participants).active(10);
        var closed = closures.close(11, new KnowledgeGapInputs.Message(1, "提问者已停用，无法继续确认"), "close");
        assertThat(closed.state()).isEqualTo(KnowledgeGapState.CLOSED);
        assertThat(fixture.gap.state()).isNotEqualTo(KnowledgeGapState.RESOLVED);
        assertThat(fixture.entries.values()).anyMatch(entry -> entry.kind().equals("CLOSE"));
        verifyNoInteractions(fixture.participants, fixture.documents);
    }

    @Test
    void allTerminalStatesRejectNewNotesAssignmentsSolutionsAndClosures() {
        for (KnowledgeGapState terminal : new KnowledgeGapState[]{KnowledgeGapState.RESOLVED, KnowledgeGapState.CLOSED, KnowledgeGapState.WITHDRAWN}) {
            var next = new KnowledgeGapMemoryFixture();
            var closure = new KnowledgeGapClosureService(next.access, next.mapper, next.commands, next.facts);
            var solution = next.solutions.submit(11, new KnowledgeGapInputs.Solution(1, "说明", null, null), "submit");
            next.actorId = 10;
            if (terminal == KnowledgeGapState.RESOLVED) {
                next.solutions.confirm(11, new KnowledgeGapInputs.Confirmation(2, solution.solutionId()), "finish");
            } else if (terminal == KnowledgeGapState.WITHDRAWN) {
                closure.withdraw(11, new KnowledgeGapInputs.Message(2, "撤回"), "finish");
            } else {
                next.actorId = 7;
                closure.close(11, new KnowledgeGapInputs.Message(2, "关闭"), "finish");
            }
            assertThat(next.gap.state()).isEqualTo(terminal);
            next.actorId = 10;
            assertStatus(409, () -> next.assignments.note(11, new KnowledgeGapInputs.Message(3, "新说明"), "note"));
            assertStatus(409, () -> closure.withdraw(11, new KnowledgeGapInputs.Message(3, "新撤回"), "withdraw"));
            next.actorId = 7;
            assertStatus(409, () -> next.assignments.assign(11, new KnowledgeGapInputs.Assignment(3, 9), "assign"));
            assertStatus(409, () -> closure.close(11, new KnowledgeGapInputs.Message(3, "新关闭"), "close"));
            next.actorId = 8;
            assertStatus(409, () -> next.solutions.submit(11, new KnowledgeGapInputs.Solution(3, "新方案", null, null), "solution"));
            assertThat(next.gap.state()).isEqualTo(terminal);
        }
    }

    @Test
    void handlingDoesNotGrantCloseOrSomeoneElsesWithdrawal() {
        assertStatus(403, () -> closures.close(11, new KnowledgeGapInputs.Message(1, "关闭"), "close"));
        assertStatus(403, () -> closures.withdraw(11, new KnowledgeGapInputs.Message(1, "撤回"), "withdraw"));
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        assertThat(fixture.events).isEmpty();
    }

    @Test
    void unsupportedEditingDeletionAndReopeningNeverInvokeBusinessWrites() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new KnowledgeGapResponseFilter()).build();
        for (var request : new MockHttpServletRequestBuilder[]{
                delete("/api/knowledge-gaps/11/withdraw"), put("/api/knowledge-gaps/11/withdraw"),
                patch("/api/admin/knowledge-gaps/11/close")}) {
            mvc.perform(request).andExpect(status().isMethodNotAllowed()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(post("/api/knowledge-gaps/11/reopen")).andExpect(status().isNotFound());
        assertThat(fixture.gap.state()).isEqualTo(KnowledgeGapState.IN_PROGRESS);
        verifyNoInteractions(fixture.mapper, fixture.identities);
    }

    private static MockHttpServletRequest request(String requestId, long version, String reason) {
        var request = new MockHttpServletRequest("POST", "/api/knowledge-gaps/11/withdraw");
        request.setContentType("application/json");
        request.addHeader("X-Request-Id", requestId);
        request.setContent(("{\"expectedVersion\":" + version + ",\"reason\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static void assertStatus(int status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(status));
    }
}
