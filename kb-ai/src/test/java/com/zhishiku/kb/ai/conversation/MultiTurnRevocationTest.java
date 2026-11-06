package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.auth.AuthenticationClient;
import com.zhishiku.kb.ai.auth.OutputPermitService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.AccountRevokedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnRevocationTest {
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.getOrSet();
    private final MultiTurnChatServiceTest fixture = new MultiTurnChatServiceTest();
    private final AuthenticationClient authentication = mock(AuthenticationClient.class);
    private final AtomicBoolean denied = new AtomicBoolean();
    private final ArrayList<ServerSentEvent<Object>> output = new ArrayList<>();

    MultiTurnRevocationTest() {
        when(authentication.check(anyString())).thenAnswer(call -> denied.get()
                ? Mono.error(new AccountRevokedException(403)) : Mono.just(new AuthenticationCheck(1, 1, false)));
        when(fixture.app.finishRevoked(any(), anyLong(), anyBoolean(), any(), any())).thenAnswer(call -> {
            assertThat(fixture.cancelled).isTrue();
            return fixture.app.finish(call.getArgument(0), "CANCELLED", call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4));
        });
    }

    @AfterEach
    void resetClock() {
        VirtualTimeScheduler.reset();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void strictAndEstimatedExecutionCancelBeforeUnknownSettlement(boolean estimated) {
        fixture.events = Flux.never();
        if (estimated) {
            fixture.estimated(100);
        }
        start();
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(fixture.cancelled).isTrue();
        verify(fixture.app).finishRevoked(fixture.key, 0, false, null, null);
        verify(fixture.settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                "CANCELLED".equals(intent.generationStatus()) && "UNKNOWN".equals(intent.usageStatus()) && intent.chargedTokens() == 12000));
        assertStopped("ACCOUNT_REVOKED");
    }

    @Test
    void slowAppendIsCancelledWithoutFlushingNewTextAndCleanupFailureDoesNotResumeOutput() {
        fixture.properties.setBatchCodepoints(1);
        fixture.events = Flux.just(new MultiTurnModel.Event("正文", false, null, null)).concatWith(Flux.never());
        when(fixture.app.append(any(), anyLong(), anyString())).thenReturn(Mono.never());
        doReturn(Mono.error(ApiException.unavailable("保存不可用")))
                .when(fixture.app).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        start();
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(fixture.cancelled).isTrue();
        assertThat(output).noneMatch(event -> "delta".equals(event.event()));
        verify(fixture.app).append(fixture.key, 1, "正");
        verify(fixture.app).finishRevoked(fixture.key, 0, false, null, null);
        assertStopped("ACCOUNT_REVOKED");
        int count = output.size();
        timer.advanceTimeBy(Duration.ofSeconds(30));
        assertThat(output).hasSize(count);
    }

    @Test
    void revocationBeforeModelInvocationKeepsNotInvokedAndNeverDispatches() {
        when(fixture.retrieval.retrieve(anyString(), anyString())).thenReturn(Mono.never());
        doAnswer(call -> fixture.app.finish(call.getArgument(0), "CANCELLED", call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4)))
                .when(fixture.app).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        start();
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        verify(fixture.app, never()).dispatch(any());
        verify(fixture.settlement).settle(org.mockito.ArgumentMatchers.argThat(intent ->
                "CANCELLED".equals(intent.generationStatus()) && "NOT_INVOKED".equals(intent.usageStatus()) && intent.chargedTokens() == 0));
        assertStopped("ACCOUNT_REVOKED");
    }

    @Test
    void completedCommitSurvivesLaterRevocationButSlowSettlementCannotReleaseDone() {
        when(fixture.settlement.settle(any())).thenReturn(Mono.never());
        start();
        verify(fixture.app).finish(eq(fixture.key), eq("COMPLETED"), anyLong(), eq(true), eq(12L), eq(2L));
        denied.set(true);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        verify(fixture.app, never()).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        assertStopped("ACCOUNT_REVOKED");
    }

    @Test
    void identityDependencyFailureStopsGenerationAsFailedWithoutInventingOffboarding() {
        fixture.events = Flux.never();
        start();
        when(authentication.check(anyString())).thenReturn(Mono.error(ApiException.unavailable("认证依赖不可达")));
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(fixture.cancelled).isTrue();
        verify(fixture.app).finish(fixture.key, "FAILED", 0, false, null, null);
        verify(fixture.app, never()).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        assertStopped("AUTHENTICATION_UNAVAILABLE");
    }

    private void start() {
        var service = fixture.service(true);
        service.setOutputPermits(new OutputPermitService(authentication, timer, () -> timer.now(TimeUnit.NANOSECONDS)));
        var prepared = service.prepare(9, "问题", "request", 0, "token").block();
        service.stream(prepared).subscribe(output::add);
    }

    private void assertStopped(String code) {
        assertThat(output).noneMatch(event -> "done".equals(event.event()));
        assertThat(output.getLast().event()).isEqualTo("error");
        assertThat(output.getLast().data().toString()).contains(code);
    }
}
