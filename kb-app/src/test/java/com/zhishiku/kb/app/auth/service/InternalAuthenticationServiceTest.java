package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.web.InternalAuthenticationController;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalAuthenticationServiceTest {
    private final String secret = "test-auth-secret-32-bytes-minimum";
    private final EpochSessionService sessions = mock(EpochSessionService.class);

    @Test
    void internalEndpointWorksWithoutConversationOrSubscriptionComponentsAndReturnsMinimalNoStoreResult() {
        var clock = Clock.systemUTC();
        var service = new InternalAuthenticationService(secret, (caller, nonce, ttl) -> true, sessions, clock);
        var controller = new InternalAuthenticationController(service);
        var databaseTime = java.time.Instant.parse("2026-01-01T00:00:00Z");
        when(sessions.check("token")).thenReturn(new EpochSessionService.Session(7, 3, EpochSessionService.Method.LOCAL, true, databaseTime));
        var request = new MockHttpServletRequest("POST", AuthenticationSignature.PATH);
        var signed = AuthenticationSignature.sign(secret, "kb-ai", "token", clock);
        request.addHeader(AuthenticationSignature.CALLER, signed.caller());
        request.addHeader(AuthenticationSignature.TIMESTAMP, Long.toString(signed.timestamp()));
        request.addHeader(AuthenticationSignature.NONCE, signed.nonce());
        request.addHeader(AuthenticationSignature.SIGNATURE, signed.signature());
        request.addHeader(AuthHeaders.SA_TOKEN, "token");
        var response = controller.check(request);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody().authEpoch()).isEqualTo(3);
        assertThat(response.getBody().mustChangePassword()).isTrue();
        assertThat(response.getBody().checkedAt()).isEqualTo(databaseTime);
    }

    @Test
    void badSignatureDoesNotQueryTokenAndNonceFailureIs503() {
        var clock = Clock.systemUTC();
        var signed = AuthenticationSignature.sign(secret, "kb-ai", "token", clock);
        var service = new InternalAuthenticationService(secret, (caller, nonce, ttl) -> {
            throw new IllegalStateException("redis failure");
        }, sessions, clock);
        assertThatThrownBy(() -> service.check("POST", AuthenticationSignature.PATH, null, "replacement", signed))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(403));
        assertThatThrownBy(() -> service.check("POST", AuthenticationSignature.PATH, null, "token", signed))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(503));
        verifyNoInteractions(sessions);
    }
}
