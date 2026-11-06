package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.auth.AuthenticationClient;
import com.zhishiku.kb.ai.auth.OutputPermitService;
import com.zhishiku.kb.ai.auth.OutputStopReporter;
import com.zhishiku.kb.common.security.AccountRevokedException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.OutputStopReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.publisher.TestPublisher;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiTurnIdentityFailureTest {
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.getOrSet();
    private final MultiTurnChatServiceTest fixture = new MultiTurnChatServiceTest();
    private final OutputStopReporter reporter = mock(OutputStopReporter.class);
    private final ArrayList<ServerSentEvent<Object>> output = new ArrayList<>();
    private boolean modelStillRunning = true;
    private final AtomicReference<OutputStopReport> stop = new AtomicReference<>();

    MultiTurnIdentityFailureTest() {
        when(fixture.app.finishRevoked(any(), anyLong(), anyBoolean(), any(), any())).thenAnswer(call ->
                fixture.app.finish(call.getArgument(0), "CANCELLED", call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4)));
        doAnswer(call -> {
            if (modelStillRunning && fixture.subscriptions.get() > 0) {
                assertThat(fixture.cancelled).isTrue();
            }
            stop.set(call.getArgument(0));
            return null;
        }).when(reporter).submit(any());
    }

    @AfterEach
    void resetClock() {
        VirtualTimeScheduler.reset();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dispatch", "append", "renew", "seal", "finish"})
    void executionRejectionBeforeRefreshCancelsAndReportsExactlyOnce(String operation) {
        var auth = mock(AuthenticationClient.class);
        when(auth.check(anyString())).thenReturn(Mono.just(new AuthenticationCheck(1, 1, false, Instant.EPOCH)));
        fixture.events = Flux.never();
        switch (operation) {
            case "dispatch" -> {
                when(fixture.app.dispatch(any())).thenReturn(Mono.error(new AccountRevokedException(401)));
            }
            case "append" -> {
                fixture.properties.setBatchCodepoints(1);
                fixture.events = Flux.just(new MultiTurnModel.Event("正文", false, null, null)).concatWith(Flux.never());
                when(fixture.app.append(any(), anyLong(), anyString())).thenReturn(Mono.error(new AccountRevokedException(401)));
            }
            case "renew" -> {
                fixture.properties.setRenewalMs(1000);
                when(fixture.app.renew(any())).thenReturn(Mono.error(new AccountRevokedException(401)));
            }
            case "seal" -> {
                when(fixture.app.seal(any(), anyString(), any(), any(), anyString()))
                        .thenReturn(Mono.error(new AccountRevokedException(401)));
            }
            case "finish" -> {
                modelStillRunning = false;
                fixture.events = Flux.empty();
                when(fixture.app.finish(any(), eq("COMPLETED"), anyLong(), anyBoolean(), any(), any()))
                        .thenReturn(Mono.error(new AccountRevokedException(401)));
            }
            default -> {
                throw new IllegalArgumentException(operation);
            }
        }
        var service = service(auth);
        service.stream(service.prepare(9, "问题", "request", 0, "token").block()).subscribe(output::add);
        timer.advanceTimeBy(Duration.ofSeconds(1));
        verify(auth).check("token");
        verify(fixture.app).finishRevoked(fixture.key, 0, false, null, null);
        verify(fixture.app, never()).finish(any(), eq("FAILED"), anyLong(), anyBoolean(), any(), any());
        assertThat(stop.get().reason()).isEqualTo("ACCOUNT_REVOKED");
        assertThat(stop.get().authEpoch()).isEqualTo(1);
        assertStopped("ACCOUNT_REVOKED");
        timer.advanceTimeBy(Duration.ofSeconds(30));
        verify(reporter).submit(any());
    }

    @ParameterizedTest
    @CsvSource({"append,false", "append,true", "finish,false", "finish,true"})
    void cleanupConfirmationUsesTheSamePermitEvenWhenResponseIsDelayed(String operation, boolean delayed) {
        var auth = mock(AuthenticationClient.class);
        when(auth.check(anyString())).thenReturn(Mono.just(new AuthenticationCheck(1, 1, false, Instant.EPOCH)));
        var model = TestPublisher.<MultiTurnModel.Event>create();
        fixture.events = model.flux();
        Sinks.One<Long> rejected = Sinks.one();
        Mono<Long> denial = delayed ? rejected.asMono() : Mono.error(new AccountRevokedException(401));
        if ("append".equals(operation)) {
            when(fixture.app.append(any(), anyLong(), anyString())).thenReturn(denial);
        } else {
            when(fixture.app.finish(any(), eq("FAILED"), anyLong(), anyBoolean(), any(), any()))
                    .thenReturn(denial.then(Mono.empty()));
        }
        var service = service(auth);
        service.stream(service.prepare(9, "问题", "request", 0, "token").block()).subscribe(output::add);
        model.next(new MultiTurnModel.Event("前缀", false, null, null));
        int beforeFailure = output.size();
        modelStillRunning = false;
        model.error(new IllegalStateException("模型中断"));
        if (delayed) {
            timer.advanceTimeBy(Duration.ofMillis(100));
            rejected.tryEmitError(new AccountRevokedException(401));
        }
        long savedPosition = "append".equals(operation) ? 0 : 1;
        verify(fixture.app).finishRevoked(fixture.key, savedPosition, false, null, null);
        verify(fixture.app).append(fixture.key, 1, "前缀");
        if ("append".equals(operation)) {
            verify(fixture.app, never()).finish(any(), eq("FAILED"), anyLong(), anyBoolean(), any(), any());
        }
        assertThat(stop.get()).isNotNull();
        assertThat(stop.get().reason()).isEqualTo("ACCOUNT_REVOKED");
        assertThat(stop.get().authEpoch()).isEqualTo(1);
        assertThat(output.subList(beforeFailure, output.size()))
                .noneMatch(event -> "delta".equals(event.event()) || "done".equals(event.event()));
        assertThat(output.getLast().data().toString()).contains("ACCOUNT_REVOKED");
        timer.advanceTimeBy(Duration.ofSeconds(30));
        verify(reporter).submit(any());
        verify(auth).check("token");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reservationRefusalReleasesQuotaOnlyAfterNoLedgerIsConfirmed(boolean unknownFinish) {
        when(fixture.app.reserve(any(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(Mono.error(new AccountRevokedException(401)));
        Mono<SettlementIntent> response = unknownFinish
                ? Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)) : Mono.empty();
        doReturn(response).when(fixture.app).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        var released = new AtomicInteger();
        when(fixture.lease.cancelAsync()).thenReturn(Mono.fromRunnable(released::incrementAndGet));
        var auth = mock(AuthenticationClient.class);
        assertThatThrownBy(() -> service(auth).prepare(9, "问题", "request", 0, "token").block())
                .isInstanceOf(ResponseStatusException.class);
        if (unknownFinish) {
            verify(fixture.lease, never()).cancelAsync();
            assertThat(released).hasValue(0);
        } else {
            verify(fixture.lease).cancelAsync();
            assertThat(released).hasValue(1);
        }
        verify(fixture.settlement, never()).settle(any());
        verify(fixture.app, never()).acknowledge(any());
        verify(reporter, never()).submit(any());
        assertThat(fixture.subscriptions).hasValue(0);
    }

    @Test
    void revokedReservationBeforeOutputUsesCancellationWithoutInventingOutputStop() {
        when(fixture.app.reserve(any(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(Mono.error(new AccountRevokedException(401)));
        var auth = mock(AuthenticationClient.class);
        assertThatThrownBy(() -> service(auth).prepare(9, "问题", "request", 0, "token").block())
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(401));
        verify(fixture.app).finishRevoked(fixture.key, 0, false, null, null);
        verify(fixture.app, never()).dispatch(any());
        verify(auth, never()).check(anyString());
        verify(reporter, never()).submit(any());
        assertThat(fixture.subscriptions).hasValue(0);
        verify(fixture.lease, never()).cancelAsync();
    }

    @ParameterizedTest
    @CsvSource({"401,false", "403,false", "401,true", "403,true"})
    void serviceAuthenticationRejectionIsNotConfusedWithConfirmedUserRevocation(int status, boolean confirmed) {
        fixture.events = Flux.never();
        var checks = new AtomicInteger();
        var wire = WebClient.builder().baseUrl("http://app.invalid").exchangeFunction(request -> {
            if (checks.incrementAndGet() == 1) {
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"userId\":1,\"authEpoch\":1,\"mustChangePassword\":false,\"checkedAt\":\"2026-01-01T00:00:00Z\"}").build());
            }
            var response = ClientResponse.create(HttpStatus.valueOf(status));
            if (confirmed) {
                response.header(AccountRevokedException.HEADER, AccountRevokedException.CODE);
            }
            return Mono.just(response.build());
        }).build();
        var service = service(new AuthenticationClient(wire, "0123456789abcdef0123456789abcdef"));
        service.stream(service.prepare(9, "问题", "request", 0, "token").block()).subscribe(output::add);
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(fixture.cancelled).isTrue();
        String code = confirmed ? "ACCOUNT_REVOKED" : "AUTHENTICATION_UNAVAILABLE";
        assertStopped(code);
        assertThat(stop.get().reason()).isEqualTo(code);
        if (confirmed) {
            verify(fixture.app).finishRevoked(fixture.key, 0, false, null, null);
        } else {
            verify(fixture.app).finish(fixture.key, "FAILED", 0, false, null, null);
            verify(fixture.app, never()).finishRevoked(any(), anyLong(), anyBoolean(), any(), any());
        }
        timer.advanceTimeBy(Duration.ofSeconds(30));
        verify(reporter).submit(any());
    }

    private MultiTurnChatService service(AuthenticationClient auth) {
        var permits = new OutputPermitService(auth, timer, () -> timer.now(TimeUnit.NANOSECONDS));
        permits.setStopReporter(reporter);
        var service = fixture.service(true);
        service.setOutputPermits(permits);
        return service;
    }

    private void assertStopped(String code) {
        assertThat(output).noneMatch(event -> "done".equals(event.event()) || "delta".equals(event.event()));
        assertThat(output.getLast().event()).isEqualTo("error");
        assertThat(output.getLast().data().toString()).contains(code);
    }
}
