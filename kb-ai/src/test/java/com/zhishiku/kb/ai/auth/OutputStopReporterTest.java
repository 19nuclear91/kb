package com.zhishiku.kb.ai.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.OutputStopReport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutputStopReporterTest {
    private final Instant anchor = Instant.parse("2026-11-06T00:00:00Z");

    @Test
    void stopIsCapturedAfterCancellationAndUsesLatestSuccessfulRequestInterval() {
        var client = mock(AuthenticationClient.class);
        var reporter = mock(OutputStopReporter.class);
        var nanos = new AtomicLong();
        var timer = VirtualTimeScheduler.create();
        var service = new OutputPermitService(client, timer, nanos::get);
        service.setStopReporter(reporter);
        when(client.check("private-token")).thenReturn(Mono.defer(() -> {
            nanos.set(10_000_000L);
            return Mono.just(new AuthenticationCheck(7, 3, false, anchor));
        }), Mono.error(ApiException.forbidden("revoked")));
        var cancelled = new AtomicBoolean();
        var report = new AtomicReference<OutputStopReport>();
        doAnswer(call -> {
            assertThat(cancelled).isTrue();
            report.set(call.getArgument(0));
            return null;
        }).when(reporter).submit(any());
        OutputPermitService.protect(service, "private-token", null, () -> Flux.never().doOnCancel(() -> cancelled.set(true)))
                .subscribe(ignored -> { }, ignored -> { });
        nanos.set(Duration.ofSeconds(5).toNanos());
        timer.advanceTimeBy(Duration.ofMillis(100));
        assertThat(report.get().checkedAt()).isEqualTo(anchor);
        assertThat(report.get().authEpoch()).isEqualTo(3);
        assertThat(report.get().afterCheckMinNanos()).isEqualTo(4_990_000_000L);
        assertThat(report.get().afterCheckMaxNanos()).isEqualTo(5_000_000_000L);
        assertThat(report.get().reason()).isEqualTo("ACCOUNT_REVOKED");
        assertThat(report.get().toString()).doesNotContain("private-token", "2026", "userId");
        timer.advanceTimeBy(Duration.ofSeconds(10));
        verify(reporter).submit(any());
        timer.dispose();
    }

    @Test
    void absentAnchorStopsOutputButNeverInventsAReceiptTimestamp() {
        var client = mock(AuthenticationClient.class);
        var reporter = mock(OutputStopReporter.class);
        var nanos = new AtomicLong();
        var timer = VirtualTimeScheduler.create();
        var service = new OutputPermitService(client, timer, nanos::get);
        service.setStopReporter(reporter);
        when(client.check("token")).thenReturn(Mono.just(new AuthenticationCheck(7, 3, false)));
        var cancelled = new AtomicBoolean();
        service.acquire("token", null).block().guard(Flux.never().doOnCancel(() -> cancelled.set(true)))
                .subscribe(ignored -> { }, ignored -> { });
        nanos.set(Duration.ofSeconds(11).toNanos());
        timer.advanceTimeBy(Duration.ofMillis(100));
        assertThat(cancelled).isTrue();
        verify(reporter).missingAnchor();
        verify(reporter, never()).submit(any());
        timer.dispose();
    }

    @Test
    void reportingIsQueuedAndQueueRejectionOrPermanentFailureCannotEscape() throws Exception {
        var client = mock(AuthenticationClient.class);
        var registry = new SimpleMeterRegistry();
        var pending = new ArrayList<Runnable>();
        var reporter = new OutputStopReporter(client, registry, pending::add);
        var report = new OutputStopReport(UUID.randomUUID(), 7, 3, anchor, 4_000_000_000L, 5_000_000_000L, "ACCOUNT_REVOKED");
        var captured = new AtomicReference<byte[]>();
        when(client.reportStop(any())).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return Mono.empty();
        });
        reporter.submit(report);
        verify(client, never()).reportStop(any());
        pending.removeFirst().run();
        assertThat(new ObjectMapper().readTree(captured.get()).path("checkedAt").asText()).isEqualTo(anchor.toString());
        assertThat(registry.get("kb.ai.output_stop_reports").tag("outcome", "REPORTED").counter().count()).isEqualTo(1);
        when(client.reportStop(any())).thenReturn(Mono.error(ApiException.forbidden("private-upstream")));
        reporter.submit(report);
        pending.removeFirst().run();
        assertThat(registry.get("kb.ai.output_stop_reports").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
        var full = new OutputStopReporter(client, registry, task -> {
            throw new RejectedExecutionException();
        });
        full.submit(report);
        assertThat(registry.get("kb.ai.output_stop_reports").tag("outcome", "DROPPED").counter().count()).isEqualTo(1);
    }
}
