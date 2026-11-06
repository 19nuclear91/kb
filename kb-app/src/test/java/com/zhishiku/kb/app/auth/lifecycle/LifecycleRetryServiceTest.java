package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleRetryServiceTest {
    private final LifecycleAuthorizer auth = mock(LifecycleAuthorizer.class);
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleInboxMapper inbox = mock(LifecycleInboxMapper.class);
    private final LifecycleReception reception = mock(LifecycleReception.class);
    private final LifecycleReceipts receipts = mock(LifecycleReceipts.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final LifecycleRetryService service = new LifecycleRetryService(auth, users, inbox, reception, receipts,
            mock(IdentityAuditService.class), manager);

    @Test
    void unauthorizedReplayNeverLooksUpOriginalReceiptOrMarksFailure() {
        doThrow(IdentityException.forbidden("缺少权限")).when(auth).require("token", Permission.IDENTITY_LIFECYCLE_MANAGE);
        assertThatThrownBy(() -> service.retry("token", 11, new LifecycleRetryService.Request("重试", "same")))
                .isInstanceOf(IdentityException.class);
        verifyNoInteractions(receipts, inbox, reception, users);
    }

    @Test
    void matchingReceiptReturnsAfterCurrentAuthorizationWithoutReprocessing() {
        when(auth.require("token", Permission.IDENTITY_LIFECYCLE_MANAGE))
                .thenReturn(new EpochSessionService.Session(7, 1, EpochSessionService.Method.LOCAL, false));
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        var result = new LifecycleReception.Result(11, true, false, 0, 0, 1);
        when(receipts.replay(eq(7L), eq("same"), eq("EVENT_RETRY"), eq("11"), any(), eq(LifecycleReception.Result.class))).thenReturn(result);
        assertThat(service.retry("token", 11, new LifecycleRetryService.Request("重试", "same"))).isEqualTo(result);
        verify(auth, times(2)).require("token", Permission.IDENTITY_LIFECYCLE_MANAGE);
        verifyNoInteractions(reception, inbox);
    }

    @Test
    void scheduledRecoveryUsesOnlyFourStoredIdsAndRecordsFailuresWithoutConfigurationDependency() {
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(inbox.dueEvents(any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(reception.reprocessWithinTransaction(2)).thenThrow(new IllegalStateException("dependency"));
        service.recover();
        verify(reception, times(4)).reprocessWithinTransaction(anyLong());
        verify(reception, never()).reprocessWithinTransaction(5);
        verify(inbox).recordRetryFailure(2);
        verifyNoInteractions(auth, receipts);
    }

    @Test
    void clientCannotSupplyReplacementPayloadEvenWhenUnknownPropertiesAreGloballyIgnored() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(() -> json.readValue("{\"reason\":\"重试\",\"requestId\":\"one\",\"employees\":[\"changed\"]}",
                LifecycleRetryService.Request.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
