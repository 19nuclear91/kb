package com.zhishiku.kb.app.parse;

import com.zhishiku.kb.app.auth.web.IdentityManagementFeedbackAdvice;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationsErrorPolicyTest {
    private final IdentityManagementFeedbackAdvice advice = new IdentityManagementFeedbackAdvice(new GlobalExceptionHandler());

    @Test
    void identityWritesKeepProjectionWhileOperationsUseOriginalHttpFailure() {
        var request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn("/api/admin/identity/users");
        var identity = advice.handle(IdentityException.forbidden("禁止操作"), request);
        assertThat(identity.getStatusCode().value()).isEqualTo(403);
        assertThat(identity.getBody()).isInstanceOfSatisfying(IdentityManagementFeedbackAdvice.Failure.class,
                value -> assertThat(value.reasonCode()).isEqualTo("FORBIDDEN"));
        for (String path : new String[]{"/api/admin/embedding/rebuilds", "/api/admin/parse/dead-letters/{deadLetterId}/replay"}) {
            when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(path);
            var operation = advice.handle(IdentityException.forbidden("禁止操作"), request);
            assertThat(operation.getStatusCode().value()).isEqualTo(403);
            assertThat(operation.getBody()).isInstanceOf(R.class);
        }
    }
}
