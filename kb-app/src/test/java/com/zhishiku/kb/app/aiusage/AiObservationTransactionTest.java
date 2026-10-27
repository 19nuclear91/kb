package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.service.AiCollectionService;
import com.zhishiku.kb.app.aiusage.service.AiObservationHeartbeat;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 Spring 事务拦截器与内存提交队列；不连接数据库，不证明 SQL 的并发行为。
 */
class AiObservationTransactionTest {
    private final AiUsageMapper mapper = mock(AiUsageMapper.class);
    private final MemoryTransactions transactions = new MemoryTransactions();
    private final MutableClock clock = new MutableClock();

    @Test
    void conflictingLivePoliciesRemainVisibleAfterOuterRollbacksAndOriginalLeaseExpiry() {
        record Beat(boolean enabled, int days, Instant until) { }
        Map<String, Beat> committed = new HashMap<>();
        when(mapper.heartbeat(anyString(), anyBoolean(), anyInt(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            var beat = new Beat(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
            transactions.stage(() -> committed.put(id, beat)); return 1;
        });
        when(mapper.inconsistentInstances(any(), anyBoolean(), anyInt())).thenAnswer(invocation -> {
            Instant now = invocation.getArgument(0); boolean enabled = invocation.getArgument(1); int days = invocation.getArgument(2);
            return (int) committed.values().stream().filter(beat -> beat.until().isAfter(now)
                    && (beat.enabled() != enabled || beat.days() != days)).count();
        });
        var shortPolicy = service(1); var longPolicy = service(180);
        shortPolicy.synchronize();
        assertThatThrownBy(longPolicy::synchronize).hasMessage("AI 观测实例配置不一致");
        assertThat(committed).hasSize(2);
        for (int second : new int[]{30, 60, 91, 121}) {
            clock.now = Instant.parse("2026-10-24T00:00:00Z").plusSeconds(second);
            assertThatThrownBy(shortPolicy::synchronize).hasMessage("AI 观测实例配置不一致");
            assertThatThrownBy(longPolicy::synchronize).hasMessage("AI 观测实例配置不一致");
            assertThat(shortPolicy.consistent()).isFalse(); assertThat(longPolicy.consistent()).isFalse();
            assertThat(shortPolicy.cleanup()).isZero(); assertThat(longPolicy.cleanup()).isZero();
            assertThat(committed.values()).allMatch(beat -> beat.until().isAfter(clock.instant()));
        }
        verify(mapper, never()).cleanupCandidates(any(), anyInt());
        verify(mapper, never()).deleteCall(anyString());
        verify(mapper, times(1)).openPeriod(any(), anyInt());
    }

    @Test
    void committedHeartbeatAndIntervalLockPrecedePolicyCheck() {
        when(mapper.heartbeat(anyString(), anyBoolean(), anyInt(), any())).thenAnswer(invocation -> {
            transactions.stage(() -> { }); return 1;
        });
        var collection = service(180);
        collection.synchronize();
        var order = inOrder(mapper);
        order.verify(mapper).heartbeat(anyString(), eq(true), eq(180), any());
        order.verify(mapper).registrationLock("ai-collection-period");
        order.verify(mapper).inconsistentInstances(any(), eq(true), eq(180));
        order.verify(mapper).openPeriod(any(), eq(180));
        doReturn(0).when(mapper).heartbeat(anyString(), anyBoolean(), anyInt(), any());
        clearInvocations(mapper);
        assertThatThrownBy(collection::synchronize).hasMessage("AI 观测实例心跳未确认");
        verify(mapper, never()).registrationLock(anyString()); verify(mapper, never()).openPeriod(any(), anyInt());
    }

    @Test
    void failedCoverageWriteRollsBackStagedDeletesAndSuccessfulCleanupCommitsBoth() {
        var row = new AiCallRow(); row.setCallId("call"); row.setGenerationStatus("COMPLETED"); row.setSettlementStatus("SETTLED");
        row.setStartedAt(clock.instant().minusSeconds(4 * 86400L));
        row.setFinalizedAt(clock.instant().minusSeconds(3 * 86400L)); row.setRetentionAnchorAt(row.getFinalizedAt());
        var callPresent = new AtomicBoolean(true); var eventsPresent = new AtomicBoolean(true);
        var watermark = new AtomicReference<Instant>(); var failCoverage = new AtomicBoolean(true);
        when(mapper.cleanupLock()).thenReturn(true);
        when(mapper.cleanupCandidates(any(), anyInt())).thenAnswer(ignored -> callPresent.get() ? List.of(row) : List.of());
        when(mapper.lockCall("call")).thenReturn(row);
        when(mapper.deletionBoundary("call")).thenReturn(row.getFinalizedAt());
        when(mapper.deleteEvents("call")).thenAnswer(ignored -> { transactions.stage(() -> eventsPresent.set(false)); return 1; });
        when(mapper.deleteCall("call")).thenAnswer(ignored -> { transactions.stage(() -> callPresent.set(false)); return 1; });
        when(mapper.advanceCoverage(any())).thenAnswer(invocation -> {
            if (failCoverage.get()) return 0;
            Instant boundary = invocation.getArgument(0); transactions.stage(() -> watermark.set(boundary)); return 1;
        });
        var collection = service(1);
        assertThatThrownBy(collection::cleanup).hasMessage("AI 观测清理界限未确认");
        assertThat(callPresent).isTrue(); assertThat(eventsPresent).isTrue(); assertThat(watermark.get()).isNull();
        failCoverage.set(false);
        assertThat(collection.cleanup()).isEqualTo(1);
        assertThat(callPresent).isFalse(); assertThat(eventsPresent).isFalse(); assertThat(watermark.get()).isEqualTo(row.getFinalizedAt());
    }

    private AiCollectionService service(int days) {
        var properties = new AiObservationProperties(new MockEnvironment()); properties.setEnabled(true); properties.setRetentionDays(days);
        var heartbeat = proxy(new AiObservationHeartbeat(mapper, properties, clock));
        return proxy(new AiCollectionService(mapper, properties, heartbeat, clock));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(advice);
        return (T) factory.getProxy();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<List<Runnable>> current = new ThreadLocal<>();
        private static final class Holder { private List<Runnable> actions; }
        void stage(Runnable action) {
            if (current.get() == null) throw new IllegalStateException("write requires transaction");
            current.get().add(action);
        }
        @Override protected Object doGetTransaction() { var holder = new Holder(); holder.actions = current.get(); return holder; }
        @Override protected boolean isExistingTransaction(Object transaction) { return ((Holder) transaction).actions != null; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            var holder = (Holder) transaction; holder.actions = new ArrayList<>(); current.set(holder.actions);
        }
        @Override protected Object doSuspend(Object transaction) {
            var holder = (Holder) transaction; var actions = holder.actions; holder.actions = null; current.remove(); return actions;
        }
        @Override @SuppressWarnings("unchecked") protected void doResume(Object transaction, Object suspended) {
            var actions = (List<Runnable>) suspended; ((Holder) transaction).actions = actions; current.set(actions);
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { ((Holder) status.getTransaction()).actions.forEach(Runnable::run); }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doCleanupAfterCompletion(Object transaction) { current.remove(); ((Holder) transaction).actions = null; }
    }
}
