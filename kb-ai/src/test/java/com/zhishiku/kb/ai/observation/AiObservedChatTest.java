package com.zhishiku.kb.ai.observation;

import com.zhishiku.kb.ai.chat.*;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 元数据、配额和模型均为内存替身，直接订阅编排流。
 */
class AiObservedChatTest {
    private final AiObservationClient client = mock(AiObservationClient.class);
    private final AiObservationClient.Session session = mock(AiObservationClient.Session.class);
    private final ChatQuotaAuditService quota = mock(ChatQuotaAuditService.class);
    private final ChatQuotaAuditService.Lease lease = mock(ChatQuotaAuditService.Lease.class);
    private final ChatService chat = mock(ChatService.class);
    private final AiObservationProperties props = new AiObservationProperties(new MockEnvironment());
    private final AiObservedChat observed = new AiObservedChat(client, quota, chat,
            new AiProperties("http://app", 8, 60000, 60), props);
    private final String leaseId = UUID.randomUUID().toString();

    AiObservedChatTest() {
        when(client.open(any())).thenReturn(Mono.just(session)); when(session.principal()).thenReturn(new QuotaPrincipal(1, 2));
        when(session.simple(any())).thenReturn(Mono.empty()); when(session.reserved(any(), anyLong())).thenReturn(Mono.empty());
        when(session.finalized(any(), any(), any(), nullable(Long.class), any())).thenReturn(Mono.empty());
        when(session.settled(any(), anyLong())).thenReturn(Mono.empty()); when(session.renew()).thenReturn(Mono.empty());
        when(quota.acquireConfirmed(any(), any(), anyInt())).thenReturn(Mono.just(lease));
        when(lease.leaseId()).thenReturn(leaseId); when(lease.reservedTokens()).thenReturn(12000L);
        when(lease.completeAsync(anyInt(), anyInt())).thenReturn(Mono.empty()); when(lease.failAsync()).thenReturn(Mono.empty());
        when(lease.cancelAsync()).thenReturn(Mono.empty()); when(lease.renewAsync()).thenReturn(Mono.empty());
    }

    @Test
    void registrationOrDispatchFailureCannotInvokeTheModelOrAcquireTwice() {
        when(client.open(any())).thenReturn(Mono.error(AiObservationClient.unavailable()));
        StepVerifier.create(observed.stream("问题", "token")).expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
        verifyNoInteractions(quota, chat);
    }

    @Test
    void knownZeroAndRepeatedCumulativeUsageRemainExactAndLegacyChargeUsesDoneFields() {
        when(chat.chat(any(), any(), any())).thenAnswer(invocation -> {
            var observer = (ChatObservation) invocation.getArgument(2);
            return observer.dispatch().thenMany(Flux.defer(() -> {
                observer.usage(ChatModelClient.StreamEvent.usage(10, 5)); observer.usage(ChatModelClient.StreamEvent.usage(0, 0));
                observer.completedUsage();
                return Flux.just(new ChatEvent.Done(12, 7, 1));
            }));
        });
        StepVerifier.create(observed.stream("问题", "token")).expectNextMatches(event -> "done".equals(event.event())).verifyComplete();
        verify(session).finalized("COMPLETED", "MODEL", new AiObservation.Usage("KNOWN", 0L, 0L), 19L, "PENDING");
        verify(lease, times(1)).completeAsync(12, 7); verify(session).settled(leaseId, 19);
        verify(quota, times(1)).acquireConfirmed(any(), any(), anyInt());
    }

    @Test
    void legacyAdaptersKeepUnknownWhileNoResultsAreNotInvoked() {
        when(chat.chat(any(), any(), any())).thenAnswer(invocation -> ((ChatObservation) invocation.getArgument(2)).dispatch()
                .thenMany(Flux.just(new ChatEvent.Done(0, 0, 1))));
        observed.stream("问题", "token").blockLast(Duration.ofSeconds(2));
        verify(session).finalized("COMPLETED", "MODEL", new AiObservation.Usage("UNKNOWN", null, null), 0L, "PENDING");
        reset(session); when(session.principal()).thenReturn(new QuotaPrincipal(1, 2));
        reset(chat);
        when(session.simple(any())).thenReturn(Mono.empty()); when(session.reserved(any(), anyLong())).thenReturn(Mono.empty());
        when(session.finalized(any(), any(), any(), anyLong(), any())).thenReturn(Mono.empty()); when(session.settled(any(), anyLong())).thenReturn(Mono.empty());
        when(chat.chat(any(), any(), any())).thenAnswer(invocation -> {
            ((ChatObservation) invocation.getArgument(2)).result("NO_RESULTS"); return Flux.just(new ChatEvent.Done(0, 0, 1));
        });
        observed.stream("问题", "token").blockLast(Duration.ofSeconds(2));
        verify(session).finalized("COMPLETED", "NO_RESULTS", new AiObservation.Usage("NOT_INVOKED", 0L, 0L), 0L, "PENDING");
    }

    @Test
    void uncertainReleaseNeverBecomesSettledAndNeverRetriesTheLeaseOrModel() {
        when(chat.chat(any(), any(), any())).thenReturn(Flux.just(new ChatEvent.Done(4, 5, 1)));
        when(lease.completeAsync(4, 5)).thenReturn(Mono.error(new IllegalStateException("private-redis")));
        StepVerifier.create(observed.stream("问题", "token")).expectNextMatches(event -> "error".equals(event.event())
                && !event.data().toString().contains("private")).verifyComplete();
        verify(lease, times(1)).completeAsync(4, 5); verify(session, never()).settled(any(), anyLong());
        verify(session).simple("REVIEW"); verify(chat, times(1)).chat(any(), any(), any());
    }

    @Test
    void cancellationIsFinalizedOnceAndRenewalDoesNotRecreateGeneration() {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenReturn(Flux.never());
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token")).thenAwait(Duration.ofMillis(1100)).thenCancel().verify();
        verify(session).renew(); verify(lease).renewAsync(); verify(lease, times(1)).cancelAsync();
        verify(session).finalized(eq("CANCELLED"), eq("CANCELLED"), any(), eq(0L), eq("PENDING"));
        verify(chat, times(1)).chat(any(), any(), any());
    }

    @Test
    void quotaRejectionAndUncertainAcquisitionHaveDifferentSettlementFacts() {
        doReturn(Mono.error(new ChatQuotaAuditService.QuotaExceededException("safe"))).when(quota).acquireConfirmed(any(), any(), anyInt());
        observed.stream("问题", "token").blockLast(Duration.ofSeconds(2));
        verify(session).finalized("REJECTED", "QUOTA_REJECTED", new AiObservation.Usage("NOT_INVOKED", 0L, 0L), 0L, "NOT_REQUIRED");
        doReturn(Mono.error(new QuotaUnavailableException("private"))).when(quota).acquireConfirmed(any(), any(), anyInt());
        observed.stream("问题", "token").blockLast(Duration.ofSeconds(2));
        verify(session).finalized("FAILED", "DEPENDENCY_UNAVAILABLE", new AiObservation.Usage("NOT_INVOKED", 0L, 0L), null, "REVIEW");
        verifyNoInteractions(chat, lease);
    }

    @Test
    void principalRefusalCannotDispatchAndNeedsNoQuotaSettlement() {
        when(quota.acquireConfirmed(any(), any(), anyInt())).thenReturn(Mono.error(new QuotaPrincipalRejectedException("主体已变化")));
        StepVerifier.create(observed.stream("问题", "token"))
                .expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
        verify(session).finalized("FAILED", "DEPENDENCY_UNAVAILABLE", new AiObservation.Usage("NOT_INVOKED", 0L, 0L), 0L, "NOT_REQUIRED");
        verifyNoInteractions(chat, lease);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"release", "settled"})
    void confirmedFinalizationStopsObservationRenewalWhileSettlementIsSlow(String slowPhase) {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenReturn(Flux.just(new ChatEvent.Done(4, 5, 1)));
        when(session.renew()).thenReturn(Mono.error(new IllegalStateException("terminal cannot renew")));
        if ("release".equals(slowPhase)) when(lease.completeAsync(4, 5)).thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1500)).then());
        else when(session.settled(leaseId, 9)).thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1500)).then());
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token"))
                .thenAwait(Duration.ofSeconds(2)).expectNextMatches(event -> "done".equals(event.event())).verifyComplete();
        verify(session, never()).renew(); verify(lease).renewAsync(); verify(lease).completeAsync(4, 5);
        verify(session).settled(leaseId, 9); verify(chat).chat(any(), any(), any());
    }

    @Test
    void renewalRefusalDuringDelayedFinalizationAckWaitsForConfirmation() {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenReturn(Flux.just(new ChatEvent.Done(4, 5, 1)));
        when(session.finalized(any(), any(), any(), nullable(Long.class), any()))
                .thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1500)).then());
        when(session.renew()).thenReturn(Mono.error(new IllegalStateException("terminal already stored")));
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token"))
                .thenAwait(Duration.ofSeconds(2)).expectNextMatches(event -> "done".equals(event.event())).verifyComplete();
        verify(session).renew(); verify(lease).renewAsync(); verify(lease).completeAsync(4, 5); verify(session).settled(leaseId, 9);
    }

    @Test
    void inFlightRenewalRefusalAfterFinalAckCannotTurnSuccessfulGenerationIntoError() {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1200))
                .thenMany(Flux.just(new ChatEvent.Done(4, 5, 1))));
        when(session.renew()).thenAnswer(ignored -> Mono.delay(Duration.ofMillis(300))
                .then(Mono.error(new IllegalStateException("terminal already stored"))));
        when(lease.completeAsync(4, 5)).thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1500)).then());
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token"))
                .thenAwait(Duration.ofSeconds(3)).expectNextMatches(event -> "done".equals(event.event())).verifyComplete();
        verify(session).renew(); verify(lease).completeAsync(4, 5); verify(session).settled(leaseId, 9);
        verify(chat).chat(any(), any(), any());
    }

    @Test
    void renewalFailureWhileGeneratingStillFailsClosedAndReleasesOnce() {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenReturn(Flux.never());
        when(session.renew()).thenReturn(Mono.error(new IllegalStateException("private-error")));
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token"))
                .thenAwait(Duration.ofMillis(1100)).expectNextMatches(event -> "error".equals(event.event())
                        && !event.data().toString().contains("private")).verifyComplete();
        verify(lease).failAsync(); verify(lease, never()).completeAsync(anyInt(), anyInt()); verify(lease, never()).cancelAsync();
        verify(session).finalized(eq("FAILED"), any(), any(), eq(0L), eq("PENDING")); verify(chat).chat(any(), any(), any());
    }

    @Test
    void failedFinalizationAckCannotHideRenewalRefusalOrReportDone() {
        props.setRenewalMs(1000);
        when(chat.chat(any(), any(), any())).thenReturn(Flux.just(new ChatEvent.Done(4, 5, 1)));
        when(session.finalized(any(), any(), any(), nullable(Long.class), any()))
                .thenAnswer(ignored -> Mono.delay(Duration.ofMillis(1500)).then(Mono.error(AiObservationClient.unavailable())));
        when(session.renew()).thenReturn(Mono.error(AiObservationClient.unavailable()));
        StepVerifier.withVirtualTime(() -> observed.stream("问题", "token"))
                .thenAwait(Duration.ofSeconds(2)).expectNextMatches(event -> "error".equals(event.event())).verifyComplete();
        verify(lease).completeAsync(4, 5); verify(session, never()).settled(any(), anyLong());
        verify(session).simple("REVIEW"); verify(chat).chat(any(), any(), any());
    }
}
