package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.ChatQuotaAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnControllerTest {
    @Test
    void budgetRejectionBeforeResponseHasFixed400CodeAndNoStore() {
        var fixture = new MultiTurnChatServiceTest();
        when(fixture.app.check(anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.error(new InputBudgetExceededException()));
        var controller = new MultiTurnChatController(fixture.service(true), fixture.properties);
        StepVerifier.create(controller.turn(9, request(), "token", null)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(InputBudgetExceededException.class).hasNoCause();
            var response = new ConversationExceptionHandler().status((ResponseStatusException) error);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(response.getBody()).containsEntry("code", "INPUT_BUDGET_EXCEEDED")
                    .containsEntry("message", "输入超出模型预算").hasSize(2);
        }).verify();
        verify(fixture.quota, never()).acquire(anyString(), anyInt());
        verify(fixture.retrieval, never()).retrieve(anyString(), anyString());
        assertThat(fixture.subscriptions).hasValue(0);
    }

    MultiTurnChatController.TurnRequest request() {
        var r = new MultiTurnChatController.TurnRequest();
        r.setQuestion("问题");
        r.setRequestId("r");
        r.setExpectedLastTurnSeq(0L);
        return r;
    }

    @Test
    void firstResponseAndReplayHaveDifferentMediaTypesAndNoSensitiveProjection() {
        var fixture = new MultiTurnChatServiceTest();
        var service = fixture.service(true);
        var controller = new MultiTurnChatController(service, fixture.properties);
        var first = controller.turn(9, request(), "token", null).block(Duration.ofSeconds(5));
        assertThat(first.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(first.getHeaders().getFirst("X-Conversation-Id")).isEqualTo("9");
        assertThat(first.getHeaders().getFirst("X-Turn-Id")).isEqualTo(fixture.key.turnId().toString());
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(fixture.subscriptions).hasValue(0);
        when(fixture.app.check(anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.just(new BeginResult(9, fixture.key.turnId(), 1, "RUNNING", true, null)));
        var replay = controller.turn(9, request(), "token", null).block();
        assertThat(replay.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(new ObjectMapper().valueToTree(replay.getBody()).fieldNames())
                .toIterable().containsExactly("conversationId", "turnId", "turnSeq", "status", "replayed");
    }

    @Test
    void unknownFieldsBadParametersAndQuotaOrOwnershipFailBeforeBodySubscription() throws Exception {
        var fixture = new MultiTurnChatServiceTest();
        var controller = new MultiTurnChatController(fixture.service(true), fixture.properties);
        var invalid = request();
        invalid.setExpectedLastTurnSeq(-1L);
        StepVerifier.create(controller.turn(9, invalid, "token", null)).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 400).verify();
        StepVerifier.create(controller.turn(9, request(), "token", "different")).expectError(ResponseStatusException.class).verify();
        for (String field : new String[]{"messages", "history", "role", "userId", "sources"})
            assertThatThrownBy(() -> new ObjectMapper().readValue("{\"question\":\"问题\",\"expectedLastTurnSeq\":0," +
                    "\"requestId\":\"r\",\"" + field + "\":[]}", MultiTurnChatController.TurnRequest.class))
                    .isInstanceOf(JsonProcessingException.class);
        when(fixture.quota.acquire(anyString(), anyInt())).thenReturn(Mono.error(new ChatQuotaAuditService.QuotaExceededException("private")));
        StepVerifier.create(controller.turn(9, request(), "token", null)).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 429).verify();
        assertThat(fixture.subscriptions).hasValue(0);
        when(fixture.app.check(anyLong(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "private")));
        StepVerifier.create(controller.turn(9, request(), "token", null)).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 404).verify();
        verify(fixture.retrieval, never()).retrieve(anyString(), anyString());
    }

    @Test
    void unconfirmedQuotaOrReservationAndChangedStableUserNeverInvokeModel() {
        var fixture = new MultiTurnChatServiceTest();
        when(fixture.app.reserve(any(), anyLong(), anyString(), anyString(), anyLong())).thenReturn(Mono.error(new IllegalStateException("private")));
        StepVerifier.create(fixture.service(true).prepare(9, "问题", "r", 0, "token")).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 503).verify();
        assertThat(fixture.subscriptions).hasValue(0);
        var changed = new MultiTurnChatServiceTest();
        when(changed.lease.userId()).thenReturn(2L);
        StepVerifier.create(changed.service(true).prepare(9, "问题", "r", 0, "token")).expectErrorMatches(e -> e instanceof ResponseStatusException s && s.getStatusCode().value() == 403).verify();
        verify(changed.app, never()).reserve(any(), anyLong(), anyString(), anyString(), anyLong());
        var moved = new MultiTurnChatServiceTest();
        when(moved.lease.departmentId()).thenReturn(20L);
        moved.service(true).prepare(9, "问题", "r", 0, "token").block();
        verify(moved.app).reserve(moved.key, 20, moved.lease.leaseId(), moved.lease.eventId(), 12000);
    }

    @Test
    void jsonNumbersAreNeverCoercedIntoQuestionOrFractionalSequence() {
        for (String body : new String[]{"{\"question\":1,\"expectedLastTurnSeq\":0}",
                "{\"question\":\"q\",\"expectedLastTurnSeq\":0.5}",
                "{\"question\":\"q\",\"expectedLastTurnSeq\":\"0\"}"})
            assertThatThrownBy(() -> new ObjectMapper().readValue(body, MultiTurnChatController.TurnRequest.class)).isInstanceOf(JsonProcessingException.class);
    }
}

