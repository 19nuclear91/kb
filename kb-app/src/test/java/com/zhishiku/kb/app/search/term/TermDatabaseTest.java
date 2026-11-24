package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermDatabaseTest {
    @Test
    void shortReadSnapshotUsesOneTransactionAndSetsTimeoutsBeforeWork() {
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var mapper = mock(TermMapper.class);
        Supplier<String> action = mock(Supplier.class);
        when(action.get()).thenReturn("ok");
        assertThat(new TermDatabase(manager, mapper).read(action)).isEqualTo("ok");
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var order = inOrder(mapper, action, manager);
        order.verify(mapper).manageTimeouts();
        order.verify(action).get();
        order.verify(manager).commit(status);
    }

    @Test
    void writeUsesReadCommittedForFreshLockRechecksAndFailureRequestsRollback() {
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var mapper = mock(TermMapper.class);
        when(mapper.manageTimeouts()).thenThrow(new QueryTimeoutException("敏感依赖内容"));
        Supplier<String> action = mock(Supplier.class);
        assertThatThrownBy(() -> new TermDatabase(manager, mapper).write(action)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.UNAVAILABLE));
        verifyNoInteractions(action);
        verify(manager).rollback(status);
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isFalse();
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
    }

    @Test
    void connectionAcquisitionFailureIsUnavailableNotSuccessfulEmptyData() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenThrow(new CannotCreateTransactionException("敏感连接信息"));
        var mapper = mock(TermMapper.class);
        Supplier<String> action = mock(Supplier.class);
        assertThatThrownBy(() -> new TermDatabase(manager, mapper).read(action)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.UNAVAILABLE));
        verifyNoInteractions(action, mapper);
    }
}
