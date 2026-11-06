package com.zhishiku.kb.ai.auth;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputPermitServiceTest {
    private final AuthenticationClient client = mock(AuthenticationClient.class);
    private final VirtualTimeScheduler timer = VirtualTimeScheduler.create();
    private final AtomicLong nanos = new AtomicLong();
    private final OutputPermitService service = new OutputPermitService(client, timer, nanos::get);
    private final AuthenticationCheck valid = new AuthenticationCheck(7, 2, false);

    @Test
    void deadlineCountsNetworkTimeAndMonotonicCheckWorksAfterSchedulerPause() {
        when(client.check("token")).thenReturn(Mono.fromSupplier(() -> {
            nanos.set(Duration.ofMillis(1500).toNanos());
            return valid;
        }));
        var permit = service.acquire("token", null).block();
        assertThat(permit.binding().userId()).isEqualTo(7);
        nanos.set(Duration.ofMillis(9999).toNanos());
        permit.require();
        nanos.set(Duration.ofSeconds(10).toNanos());
        assertThatThrownBy(permit::require).isInstanceOf(OutputRevokedException.class);
        assertThat(permit.isDisposed()).isTrue();
    }

    @Test
    void initialTimeoutAndWrongBindingNeverSubscribeToSource() {
        when(client.check("token")).thenReturn(Mono.just(valid));
        var subscribed = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        OutputPermitService.protect(service, "token", new OutputPermitService.Binding(8, 2, "turn"),
                        () -> Flux.just("secret").doOnSubscribe(ignored -> subscribed.set(true)))
                .subscribe(ignored -> {}, error::set);
        assertThat(subscribed).isFalse();
        assertThat(error.get()).isInstanceOf(OutputRevokedException.class);
        when(client.check("token")).thenReturn(Mono.never());
        service.acquire("token", null).subscribe(ignored -> {}, error::set);
        timer.advanceTimeBy(Duration.ofSeconds(2));
        assertThat(((OutputRevokedException) error.get()).code()).isEqualTo("AUTHENTICATION_UNAVAILABLE");
    }

    @Test
    void refreshFailureCancelsSilentSourceWithoutWaitingForDataOrPersistence() {
        when(client.check("token")).thenReturn(Mono.just(valid), Mono.error(IdentityException.forbidden("private")));
        var cancelled = new AtomicBoolean();
        var error = new AtomicReference<Throwable>();
        OutputPermitService.protect(service, "token", null, () -> Flux.never().doOnCancel(() -> cancelled.set(true)))
                .subscribe(ignored -> {}, error::set);
        nanos.set(Duration.ofSeconds(3).toNanos());
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(cancelled).isTrue();
        assertThat(((OutputRevokedException) error.get()).code()).isEqualTo("ACCOUNT_REVOKED");
        assertThat(error.get().getMessage()).doesNotContain("private");
    }

    @Test
    void refreshCannotReopenExpiredPermitWithLateSuccess() {
        Sinks.One<AuthenticationCheck> response = Sinks.one();
        when(client.check("token")).thenReturn(Mono.just(valid), response.asMono());
        var permit = service.acquire("token", null).block();
        nanos.set(Duration.ofSeconds(3).toNanos());
        timer.advanceTimeBy(Duration.ofSeconds(3));
        nanos.set(Duration.ofSeconds(11).toNanos());
        response.tryEmitValue(valid);
        assertThat(permit.isDisposed()).isTrue();
        assertThatThrownBy(permit::require).isInstanceOf(OutputRevokedException.class);
    }

    @Test
    void oldExecutionResponseDoesNotAuthorizeAnotherTurnOrEpoch() {
        when(client.check("token")).thenReturn(Mono.just(valid), Mono.just(new AuthenticationCheck(7, 3, false)));
        var permit = service.acquire("token", new OutputPermitService.Binding(7, 2, "first")).block();
        nanos.set(Duration.ofSeconds(3).toNanos());
        timer.advanceTimeBy(Duration.ofSeconds(3));
        assertThat(permit.isDisposed()).isTrue();
        when(client.check("token")).thenReturn(Mono.just(valid));
        assertThatThrownBy(() -> OutputPermitService.protect(service, "token", new OutputPermitService.Binding(7, 2, "second"),
                () -> OutputPermitService.protect(service, "token", new OutputPermitService.Binding(7, 2, "first"),
                        () -> Flux.just("secret"))).blockLast()).isInstanceOf(OutputRevokedException.class);
    }

    @Test
    void queuedDataIsCheckedAtFinalOutputAndCannotSurviveExpiredPermit() {
        when(client.check("token")).thenReturn(Mono.just(valid));
        var saved = Sinks.<String>one();
        var output = new ArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        OutputPermitService.protect(service, "token", null, () ->
                        OutputPermitService.protect(service, "token", null, () -> Flux.just("citation", "done"))
                                .concatMap(value -> saved.asMono().thenReturn(value)))
                .subscribe(output::add, failure::set);
        nanos.set(Duration.ofSeconds(10).toNanos());
        saved.tryEmitValue("saved");
        assertThat(output).isEmpty();
        assertThat(failure.get()).isInstanceOf(OutputRevokedException.class);
    }

    @Test
    void refreshTimeoutAndDisposedPermitIgnoreLateCallbacks() {
        Sinks.One<AuthenticationCheck> response = Sinks.one();
        when(client.check("token")).thenReturn(Mono.just(valid), response.asMono());
        var timedService = new OutputPermitService(client, timer, () -> timer.now(TimeUnit.NANOSECONDS));
        var permit = timedService.acquire("token", null).block();
        var stopped = new AtomicReference<Throwable>();
        permit.guard(Flux.never()).subscribe(ignored -> {}, stopped::set);
        timer.advanceTimeBy(Duration.ofSeconds(5));
        assertThat(permit.isDisposed()).isTrue();
        assertThat(((OutputRevokedException) stopped.get()).code()).isEqualTo("AUTHENTICATION_UNAVAILABLE");
        response.tryEmitValue(valid);
        assertThatThrownBy(permit::require).isInstanceOf(OutputRevokedException.class);
    }

    @Test
    void unknownDependencyAndMissingConfigurationFailClosed() {
        when(client.check("token")).thenReturn(Mono.error(ApiException.unavailable("secret")));
        assertThatThrownBy(() -> service.acquire("token", null).block()).isInstanceOf(OutputRevokedException.class);
        assertThatThrownBy(() -> OutputPermitService.protect(null, "token", null, () -> Flux.just("data")).blockLast())
                .isInstanceOf(OutputRevokedException.class);
    }
}
