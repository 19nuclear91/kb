package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleReadinessTest {
    @Test
    void enabledReceiverRequiresProvenEmergencyBeforeStarting() {
        var guard = mock(ManagementInvariant.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var readiness = new LifecycleReadiness(guard, manager, true);
        assertThatThrownBy(() -> readiness.run(null)).isInstanceOf(IdentityException.class);
        verify(manager).rollback(status);
        var order = inOrder(guard);
        order.verify(guard).lock();
        order.verify(guard).emergencyRemainsAfter(any());
        when(guard.emergencyRemainsAfter(any())).thenReturn(true);
        readiness.run(null);
        verify(manager).commit(status);
    }

    @Test
    void disabledReceiverDoesNotCreateOrRemovePersistentGuarantees() {
        var guard = mock(ManagementInvariant.class);
        var manager = mock(PlatformTransactionManager.class);
        new LifecycleReadiness(guard, manager, false).run(null);
        verifyNoInteractions(guard, manager);
    }
}
