package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapAssignmentService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapClosureService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCommandService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapCreateService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapEventService;
import com.zhishiku.kb.app.knowledgegap.service.KnowledgeGapSolutionService;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 实际事务注解/代理配合快照仓储；只验证事务编排，不证明数据库锁、约束或隔离级别。 */
final class KnowledgeGapTransactionFixture {
    final KnowledgeGapMemoryFixture memory = new KnowledgeGapMemoryFixture();
    final Map<UUID, List<Long>> recipients = new HashMap<>();
    final Set<Long> guards = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    final Transactions transactions = new Transactions();
    final KnowledgeGapMapper repository;
    final KnowledgeGapCommandService commands;
    final KnowledgeGapEventService events;
    final KnowledgeGapCreateService creates;
    final KnowledgeGapAssignmentService assignments;
    final KnowledgeGapSolutionService solutions;
    final KnowledgeGapClosureService closures;
    int openCount;
    String failureAt;
    boolean zeroResult;

    KnowledgeGapTransactionFixture() {
        var factory = new ProxyFactory(memory.mapper);
        factory.addAdvice((MethodInterceptor) invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            String method = invocation.getMethod().getName();
            calls.add(method);
            if (method.equals(failureAt)) {
                if (zeroResult) {
                    return method.equals("create") ? null : 0;
                }
                throw new DataAccessResourceFailureException("secret shared text and jdbc credentials");
            }
            return invocation.proceed();
        });
        repository = (KnowledgeGapMapper) factory.getProxy();
        commands = proxy(new KnowledgeGapCommandService(repository, memory.access));
        events = proxy(new KnowledgeGapEventService(repository));
        creates = proxy(new KnowledgeGapCreateService(memory.access, repository, commands, events));
        assignments = proxy(new KnowledgeGapAssignmentService(memory.access, repository, commands,
                mock(KnowledgeGapAssigneeService.class), events));
        solutions = proxy(new KnowledgeGapSolutionService(memory.access, repository, commands, events, memory.documents, memory.participants));
        closures = proxy(new KnowledgeGapClosureService(memory.access, repository, commands, events));
        doAnswer(call -> guards.add(call.getArgument(0)) ? 1 : 0).when(memory.mapper).ensureActorGuard(anyLong());
        doAnswer(call -> guards.contains(call.getArgument(0)) ? call.getArgument(0) : null)
                .when(memory.mapper).lockActorGuard(anyLong());
        doAnswer(call -> (long) openCount).when(memory.mapper).countOpen(anyLong());
        doAnswer(call -> {
            long actor = call.getArgument(0);
            KnowledgeGapInputs.Creation input = call.getArgument(1);
            memory.gap = new KnowledgeGapRows.Gap(11, actor, input.category(), input.source(), input.description(),
                    call.getArgument(2), KnowledgeGapState.SUBMITTED, 0, null, 0, null,
                    call.getArgument(3), Instant.EPOCH, Instant.EPOCH);
            openCount++;
            return memory.gap;
        }).when(memory.mapper).create(anyLong(), any(), any(), any());
        doAnswer(call -> {
            UUID id = call.getArgument(0);
            List<Long> users = call.getArgument(1);
            recipients.put(id, List.copyOf(users));
            return users.size();
        }).when(memory.mapper).insertRecipients(any(), any());
        doAnswer(call -> {
            recipients.put(call.getArgument(0), List.of(7L));
            return 1;
        }).when(memory.mapper).insertReceptionRecipients(any());
    }

    Snapshot snapshot() {
        return new Snapshot(memory.gap, Map.copyOf(memory.bindings), Map.copyOf(memory.entries),
                List.copyOf(memory.events), Map.copyOf(recipients), Set.copyOf(guards), openCount);
    }

    private void restore(Snapshot snapshot) {
        memory.gap = snapshot.gap();
        memory.bindings.clear();
        memory.bindings.putAll(snapshot.bindings());
        memory.entries.clear();
        memory.entries.putAll(snapshot.entries());
        memory.events.clear();
        memory.events.addAll(snapshot.events());
        recipients.clear();
        recipients.putAll(snapshot.recipients());
        guards.clear();
        guards.addAll(snapshot.guards());
        openCount = snapshot.openCount();
    }

    private <T> T proxy(T target) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        @SuppressWarnings("unchecked")
        T result = (T) factory.getProxy();
        return result;
    }

    record Snapshot(KnowledgeGapRows.Gap gap, Map<String, KnowledgeGapRows.Command> bindings,
                    Map<UUID, KnowledgeGapRows.Entry> entries, List<KnowledgeGapRows.Event> events,
                    Map<UUID, List<Long>> recipients, Set<Long> guards, int openCount) {
    }

    final class Transactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Holder> current = new ThreadLocal<>();
        final List<Integer> timeouts = new ArrayList<>();
        int commits;
        int rollbacks;

        private final class Holder implements SmartTransactionObject {
            Snapshot snapshot;
            boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() {
                return rollbackOnly;
            }

            @Override
            public void flush() {
                // 内存快照没有待刷新资源。
            }
        }

        @Override
        protected Object doGetTransaction() {
            return current.get() == null ? new Holder() : current.get();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Holder) transaction).snapshot != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            var holder = (Holder) transaction;
            holder.snapshot = snapshot();
            current.set(holder);
            timeouts.add(definition.getTimeout());
            assertThat(definition.isReadOnly()).isFalse();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            restore(((Holder) status.getTransaction()).snapshot);
            rollbacks++;
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((Holder) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }
    }
}
