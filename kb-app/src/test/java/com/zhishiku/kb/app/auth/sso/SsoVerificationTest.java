package com.zhishiku.kb.app.auth.sso;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SsoVerificationTest {
    @Test void wholeVerificationTimeoutCancelsWorkWithoutWaitingForRealTimeOrRedis() throws Exception {
        var executor = mock(ExecutorService.class);
        @SuppressWarnings("unchecked") Future<VerifiedExternalIdentity> future = mock(Future.class);
        when(executor.submit(any(Callable.class))).thenReturn(future);
        when(future.get(30, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        assertThatThrownBy(() -> new SsoVerification(executor).verify(mock(ExternalLoginProvider.class), Map.of(), "v"))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.code()).isEqualTo("UPSTREAM_UNAVAILABLE"));
        verify(future).cancel(true);
    }
}
