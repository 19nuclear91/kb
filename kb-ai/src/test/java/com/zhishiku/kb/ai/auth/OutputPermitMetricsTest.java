package com.zhishiku.kb.ai.auth;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputPermitMetricsTest {
    @Test
    void revocationBoundUsesLatestConfirmedRequestAndUnknownIsNotZeroSuccess() {
        var nanos = new AtomicLong();
        var registry = new SimpleMeterRegistry();
        var metrics = new OutputPermitMetrics(registry, nanos::get);
        var handle = metrics.begin(0, Duration.ofSeconds(10).toNanos());
        handle.refreshed(Duration.ofSeconds(16).toNanos());
        nanos.set(Duration.ofSeconds(9).toNanos());
        handle.stopped("ACCOUNT_REVOKED");
        assertThat(registry.get("kb.ai.output_permit.revocation_stop_upper_bound").timer().totalTime(TimeUnit.SECONDS)).isEqualTo(3);
        var unknown = metrics.begin(nanos.get(), Duration.ofSeconds(19).toNanos());
        unknown.stopped("AUTHENTICATION_UNAVAILABLE");
        assertThat(registry.get("kb.ai.output_permit.revocation_stop_upper_bound").timer().count()).isEqualTo(1);
    }

    @Test
    void pausedTimerShowsOverdueThenRecordsOnlyObservedLocalStopWithoutIdentifiers() {
        var nanos = new AtomicLong();
        var registry = new SimpleMeterRegistry();
        var metrics = new OutputPermitMetrics(registry, nanos::get);
        var client = mock(AuthenticationClient.class);
        when(client.check("secret-token")).thenReturn(Mono.just(new AuthenticationCheck(7, 2, false)));
        var timer = VirtualTimeScheduler.create();
        var service = new OutputPermitService(client, timer, nanos::get);
        service.setMetrics(metrics);
        var cancelled = new AtomicBoolean();
        var permit = service.acquire("secret-token", null).block();
        permit.guard(Flux.never().doOnCancel(() -> cancelled.set(true))).subscribe(ignored -> {}, ignored -> {});
        assertThat(registry.find("kb.ai.output_permit.observed_age").timer()).isNull();
        nanos.set(Duration.ofSeconds(12).toNanos());
        assertThat(registry.get("kb.ai.output_permit.overdue_seconds").gauge().value()).isEqualTo(2);
        timer.advanceTimeBy(Duration.ofMillis(100));
        assertThat(cancelled).isTrue();
        assertThat(registry.get("kb.ai.output_permit.active").gauge().value()).isZero();
        assertThat(registry.get("kb.ai.output_permit.stop_overdue").timer().totalTime(TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(registry.get("kb.ai.output_permit.deadline_misses").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
            assertThat(tag.getKey()).isEqualTo("reason");
            assertThat(tag.getValue()).isEqualTo("OUTPUT_PERMIT_EXPIRED");
        }));
        timer.dispose();
    }

    @Test
    void acquisitionDependencyFailureIsNotAStopAndNormalCloseDoesNotCreateLatencyEvidence() {
        var nanos = new AtomicLong();
        var registry = new SimpleMeterRegistry();
        var metrics = new OutputPermitMetrics(registry, nanos::get);
        var client = mock(AuthenticationClient.class);
        var timer = VirtualTimeScheduler.create();
        var service = new OutputPermitService(client, timer, nanos::get);
        service.setMetrics(metrics);
        when(client.check("token")).thenReturn(Mono.error(ApiException.unavailable("private detail")));
        service.acquire("token", null).subscribe(ignored -> {}, ignored -> {});
        assertThat(registry.get("kb.ai.output_permit.acquisition_failures").tag("reason", "AUTHENTICATION_UNAVAILABLE").counter().count()).isEqualTo(1);
        assertThat(registry.find("kb.ai.output_permit.stops").counter()).isNull();
        var handle = metrics.begin(0, 10_000_000_000L);
        handle.closed();
        assertThat(registry.find("kb.ai.output_permit.observed_age").timer()).isNull();
        assertThat(registry.get("kb.ai.output_permit.active").gauge().value()).isZero();
        timer.dispose();
    }
}
