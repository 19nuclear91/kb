package com.zhishiku.kb.ai.chat;

import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatQuotaAuditServiceTest {

    private static final AiProperties PROPS =
            new AiProperties("http://localhost:8081", 8, 60_000L, 60);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-01T13:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(strings = {"user", "department", "disabled", "empty"})
    void confirmedAcquisitionRejectsChangedOrUnavailablePrincipalBeforeRedis(String scenario) {
        var redis = mock(ReactiveStringRedisTemplate.class);
        var sink = mock(ChatAuditSink.class); var principals = mock(QuotaPrincipalClient.class);
        Mono<QuotaPrincipal> current = switch (scenario) {
            case "user" -> Mono.just(new QuotaPrincipal(8, 42));
            case "department" -> Mono.just(new QuotaPrincipal(7, 99));
            case "disabled" -> Mono.error(new QuotaPrincipalRejectedException("主体已停用"));
            default -> Mono.empty();
        };
        when(principals.resolve("token")).thenReturn(current);
        var service = new ChatQuotaAuditService(PROPS, sink, principals, redis, CLOCK);
        StepVerifier.create(service.acquireConfirmed(new QuotaPrincipal(7, 42), "token", 3))
                .expectError(QuotaPrincipalRejectedException.class).verify();
        verify(principals).resolve("token"); Mockito.verifyNoInteractions(redis, sink);
    }

    @Test
    void confirmedAcquisitionRechecksAtSubscriptionAndAcquiresExactlyOnce() {
        var redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        var sink = mock(ChatAuditSink.class); var principals = mock(QuotaPrincipalClient.class);
        var expected = new QuotaPrincipal(7, 42);
        when(principals.resolve("token")).thenReturn(Mono.just(expected));
        var service = new ChatQuotaAuditService(PROPS, sink, principals, redis, CLOCK);
        var acquisition = service.acquireConfirmed(expected, "token", 3);
        Mockito.verifyNoInteractions(principals, redis);
        assertThat(acquisition.block()).isNotNull();
        var order = Mockito.inOrder(principals, redis);
        order.verify(principals).resolve("token"); order.verify(redis).execute(any(), anyList(), anyList());
        Mockito.verifyNoInteractions(sink);
    }

    @Test
    void acquiresAndReleasesDepartmentLeaseAndAuditsWithoutToken() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("secret-token"))
                .thenReturn(Mono.just(new QuotaPrincipal(7L, 42L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);

        ChatQuotaAuditService.Lease lease = service.acquire("secret-token", 12).block();
        assertThat(lease).isNotNull();
        lease.complete(10, 4);

        verify(sink).record(ArgumentMatchers.argThat(event ->
                event.userId() == 7L
                        && event.departmentId() == 42L
                        && event.questionChars() == 12
                        && event.status().equals("completed")
                        && event.promptTokens() == 10
                        && event.completionTokens() == 4
                        && !event.principalFingerprint().equals("secret-token")));

        @SuppressWarnings("unchecked")
        var arguments = ArgumentCaptor.forClass(List.class);
        verify(redis, Mockito.times(2))
                .execute(any(), anyList(), arguments.capture());
        assertThat(arguments.getAllValues().get(1)).contains("14", "60");
    }

    @Test
    void usesCurrentDepartmentLedgerKeyFromResolvedPrincipal() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token"))
                .thenReturn(Mono.just(new QuotaPrincipal(7L, 99L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);
        assertThat(service.acquire("token", 3).block()).isNotNull();

        @SuppressWarnings("unchecked")
        var keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(), keys.capture(), anyList());
        assertThat((List<String>) keys.getValue()).allMatch(key -> key.contains(":dept:99:"));
    }

    @Test
    void rejectsWhenDepartmentQuotaScriptDeniesAndWritesAudit() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(0L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token"))
                .thenReturn(Mono.just(new QuotaPrincipal(8L, 42L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);

        StepVerifier.create(service.acquire("token", 9))
                .expectError(ChatQuotaAuditService.QuotaExceededException.class)
                .verify();

        verify(sink).record(ArgumentMatchers.argThat(event ->
                event.departmentId() == 42L && event.status().equals("quota_rejected")));
    }

    @Test
    void rejectsWhenTokenBudgetScriptDeniesAndWritesTokenAudit() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(-2L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token"))
                .thenReturn(Mono.just(new QuotaPrincipal(8L, 42L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);

        StepVerifier.create(service.acquire("token", 9))
                .expectError(ChatQuotaAuditService.TokenQuotaExceededException.class)
                .verify();

        verify(sink).record(ArgumentMatchers.argThat(event ->
                event.departmentId() == 42L && event.status().equals("token_quota_rejected")));
    }

    @Test
    void renewsLeaseAndFinishesOnlyOnce() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token"))
                .thenReturn(Mono.just(new QuotaPrincipal(7L, 42L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);

        ChatQuotaAuditService.Lease lease = service.acquire("token", 9).block();
        assertThat(lease).isNotNull();
        lease.renewAsync().block();
        lease.completeAsync(3, 2).block();
        lease.cancelAsync().block();

        // acquire + renew + release；cancel 不能重复释放或重复审计。
        verify(redis, Mockito.times(3)).execute(any(), anyList(), anyList());
        verify(sink).record(ArgumentMatchers.any(ChatAuditEvent.class));
    }

    @Test
    void retriesReleaseByRecreatingRedisCommand() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L), Flux.error(new IllegalStateException("connection reset")),
                Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token"))
                .thenReturn(Mono.just(new QuotaPrincipal(7L, 42L)));

        ChatQuotaAuditService service = new ChatQuotaAuditService(
                PROPS, sink, principals, redis, CLOCK);

        ChatQuotaAuditService.Lease lease = service.acquire("token", 9).block();
        assertThat(lease).isNotNull();
        lease.completeAsync(3, 2).block();

        verify(redis, Mockito.times(3)).execute(any(), anyList(), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled"})
    void failureOrCancellationReleasesOnceWithoutInventingUsage(String status) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        doReturn(Flux.just(1L)).when(redis).execute(any(), anyList(), anyList());
        ChatAuditSink sink = mock(ChatAuditSink.class);
        QuotaPrincipalClient principals = mock(QuotaPrincipalClient.class);
        when(principals.resolve("token")).thenReturn(Mono.just(new QuotaPrincipal(7L, 42L)));
        ChatQuotaAuditService service = new ChatQuotaAuditService(PROPS, sink, principals, redis, CLOCK);
        ChatQuotaAuditService.Lease lease = service.acquire("token", 9).block();
        assertThat(lease).isNotNull();

        StepVerifier.create(status.equals("failed") ? lease.failAsync() : lease.cancelAsync())
                .verifyComplete();
        StepVerifier.create(lease.completeAsync(37, 11)).verifyComplete();

        verify(sink).record(ArgumentMatchers.argThat(event -> event.status().equals(status)
                && event.promptTokens() == 0 && event.completionTokens() == 0));
        @SuppressWarnings("unchecked")
        var arguments = ArgumentCaptor.forClass(List.class);
        verify(redis, Mockito.times(2)).execute(any(), anyList(), arguments.capture());
        assertThat(arguments.getAllValues().get(1).get(1)).isEqualTo("0");
    }
}
