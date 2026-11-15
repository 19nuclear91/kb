package com.zhishiku.kb.app.document.applicability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.mapper.ValidityCommandMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityChange;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityTarget;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityUpdate;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.model.ValidityCommandRow;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityWriteService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityCommands;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipAccess;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipConfirmService;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipWriteService;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 实际事务注解与代理配合内存快照，仅验证编排，不证明数据库约束、锁或真实回滚。 */
class ValidityTransactionTest {
    @ParameterizedTest
    @ValueSource(strings = {"history", "audit", "receipt"})
    void applicabilityFailureRollsBackDeclarationHistoryAuditAndReceiptThenSameRequestCanSucceed(String failure) {
        var f = new Fixture();
        var before = f.snapshot();
        var input = new ApplicabilityUpdate(null, UntilKind.DATE, LocalDate.of(2026, 12, 31), "不进入审计的原因", 0);
        f.failure = failure;
        assertThatThrownBy(() -> f.applicability.update(7, 10, 20, input, "request")).isInstanceOf(IllegalStateException.class);
        assertThat(f.snapshot()).isEqualTo(before);
        assertThat(f.transactions.rollbacks).isEqualTo(1);
        f.failure = null;
        var receipt = f.applicability.update(7, 10, 20, input, "request");
        assertThat(f.applicability.update(7, 10, 20, input, "request")).isEqualTo(receipt.replay());
        assertThat(f.history).hasSize(1);
        assertThat(f.audits).hasSize(1).allSatisfy(value -> assertThat(value).doesNotContain("不进入审计的原因"));
        assertThat(f.receipts).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit", "receipt"})
    void assignmentFailureRollsBackFirstAssignmentAndSameRequestCanBeRetried(String failure) {
        var f = new Fixture();
        var before = f.snapshot();
        var input = new StewardshipInputs.Update(7L, 3, 0);
        f.failure = failure;
        assertThatThrownBy(() -> f.assignments.update(7, 10, input, "request")).isInstanceOf(IllegalStateException.class);
        assertThat(f.snapshot()).isEqualTo(before);
        f.failure = null;
        var receipt = f.assignments.update(7, 10, input, "request");
        assertThat(receipt.dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(f.assignments.update(7, 10, input, "request")).isEqualTo(receipt.replay());
        assertThat(f.audits).hasSize(1);
        assertThat(f.receipts).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit", "receipt"})
    void confirmationFailureRestoresPreviousDueDateAndConfirmationFacts(String failure) {
        var f = new Fixture();
        f.assignments.update(7, 10, new StewardshipInputs.Update(7L, 3, 0), "assign");
        var before = f.snapshot();
        when(f.date.today()).thenReturn(LocalDate.of(2026, 12, 12));
        var input = new StewardshipInputs.Confirm(1);
        f.failure = failure;
        assertThatThrownBy(() -> f.confirms.confirm(7, 10, input, "confirm")).isInstanceOf(IllegalStateException.class);
        assertThat(f.snapshot()).isEqualTo(before);
        f.failure = null;
        var receipt = f.confirms.confirm(7, 10, input, "confirm");
        assertThat(receipt.lastConfirmedOn()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(receipt.dueDate()).isEqualTo(LocalDate.of(2027, 3, 12));
        assertThat(f.confirms.confirm(7, 10, input, "confirm")).isEqualTo(receipt.replay());
        assertThat(f.audits).hasSize(2);
        assertThat(f.receipts).hasSize(2);
    }

    private record Snapshot(ApplicabilityRow declaration, StewardshipRow stewardship, List<ApplicabilityChange> history,
                            List<String> audits, Map<String, ValidityCommandRow> receipts) {
    }

    private static final class Fixture {
        final ApplicabilityMapper mapper = mock(ApplicabilityMapper.class);
        final StewardshipMapper stewards = mock(StewardshipMapper.class);
        final ValidityCommandMapper commandMapper = mock(ValidityCommandMapper.class);
        final IdentityAuditService audit = mock(IdentityAuditService.class);
        final ValidityAccess access = mock(ValidityAccess.class);
        final BusinessDate date = mock(BusinessDate.class);
        final List<ApplicabilityChange> history = new ArrayList<>();
        final List<String> audits = new ArrayList<>();
        final Map<String, ValidityCommandRow> receipts = new HashMap<>();
        final Transactions transactions = new Transactions();
        final ApplicabilityWriteService applicability;
        final StewardshipWriteService assignments;
        final StewardshipConfirmService confirms;
        ApplicabilityRow declaration = new ApplicabilityRow(10, 20, null, UntilKind.UNDECLARED, null, 0, false, null, true);
        StewardshipRow stewardship;
        String failure;

        Fixture() {
            when(date.today()).thenReturn(LocalDate.of(2026, 11, 30));
            var handle = mock(PermissionService.PrincipalHandle.class);
            when(access.current(7)).thenReturn(handle);
            when(access.has(handle, 10, "view")).thenReturn(true);
            var target = new ApplicabilityTarget(10, 20, 2, "active", false, "REVIEW_V1", "PUBLISHED", false);
            when(mapper.lockVersion(10, 20)).thenReturn(target);
            when(mapper.target(10, 20)).thenReturn(target);
            when(mapper.get(10, 20)).thenAnswer(call -> declaration);
            doAnswer(call -> {
                check(null);
                ApplicabilityRow row = call.getArgument(0);
                declaration = new ApplicabilityRow(10, 20, row.effectiveFrom(), row.untilKind(), row.effectiveUntil(),
                        row.applicabilityVersion(), row.corrected(), OffsetDateTime.parse("2026-11-30T00:00:00Z"), true);
                return 1;
            }).when(mapper).insert(any());
            doAnswer(call -> {
                check("history");
                history.add(call.getArgument(0));
                return 1;
            }).when(mapper).appendChange(any());
            var document = new StewardshipDocument(10, 2, "active", false);
            when(stewards.shareDocument(10)).thenReturn(document);
            when(stewards.document(10)).thenReturn(document);
            when(stewards.eligible(10, 7)).thenReturn(true);
            when(stewards.lock(10)).thenAnswer(call -> stewardship);
            when(stewards.get(10)).thenAnswer(call -> stewardship);
            doAnswer(call -> saveStewardship(call.getArgument(0))).when(stewards).insert(any());
            doAnswer(call -> saveStewardship(call.getArgument(0))).when(stewards).update(any());
            doAnswer(call -> {
                check(null);
                return 1;
            }).when(commandMapper).lockRequest(anyString());
            when(commandMapper.get(anyString(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(0) + ":" + call.getArgument(1)));
            doAnswer(call -> {
                check("receipt");
                ValidityCommandRow row = call.getArgument(0);
                receipts.put(row.operationScope() + ":" + row.requestId(), row);
                return 1;
            }).when(commandMapper).insert(any());
            doAnswer(call -> {
                check("audit");
                audits.add(call.getArgument(5) + ":" + call.getArgument(6));
                return null;
            }).when(audit).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
            var commands = proxy(new ValidityCommands(commandMapper, new ObjectMapper()));
            applicability = proxy(new ApplicabilityWriteService(mapper, access, commands, audit, date));
            assignments = proxy(new StewardshipWriteService(stewards, mock(StewardshipAccess.class), commands, audit, date));
            confirms = proxy(new StewardshipConfirmService(stewards, access, commands, audit, date));
        }

        private int saveStewardship(StewardshipRow row) {
            check(null);
            stewardship = new StewardshipRow(10, row.responsibleUserId(), row.cycleMonths(), row.firstAssignedOn(),
                    row.lastConfirmedOn(), row.lastConfirmedBy(), row.dueDate(), row.stewardshipVersion(), OffsetDateTime.parse("2026-11-30T00:00:00Z"));
            return 1;
        }

        private void check(String step) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if (step != null && step.equals(failure)) {
                throw new IllegalStateException("测试边界故障");
            }
        }

        Snapshot snapshot() {
            return new Snapshot(declaration, stewardship, List.copyOf(history), List.copyOf(audits), Map.copyOf(receipts));
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

        final class Transactions extends AbstractPlatformTransactionManager {
            private final ThreadLocal<Holder> current = new ThreadLocal<>();
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
                    // 内存快照无待刷新资源。
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
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                assertThat(definition.isReadOnly()).isFalse();
                var holder = (Holder) transaction;
                holder.snapshot = snapshot();
                current.set(holder);
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                // 仓储状态在提交后保留。
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
                var before = ((Holder) status.getTransaction()).snapshot;
                declaration = before.declaration();
                stewardship = before.stewardship();
                history.clear();
                history.addAll(before.history());
                audits.clear();
                audits.addAll(before.audits());
                receipts.clear();
                receipts.putAll(before.receipts());
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
}
