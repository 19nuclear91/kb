package com.zhishiku.kb.common.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticationSignatureTest {
    @Test
    void outputEvidenceBindsBodyOperationCallerAndNonceWithoutGrantingCheckPermission() {
        byte[] body = "{\"userId\":7}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var signed = AuthenticationSignature.signStop(secret, body, clock);
        assertThatThrownBy(() -> AuthenticationSignature.verifyStop(secret, "POST", AuthenticationSignature.STOP_PATH,
                new byte[]{1}, signed, clock, nonces)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verify(signed, "token", clock)).isInstanceOf(SecurityException.class);
        var gateway = new AuthenticationSignature.Headers("kb-gateway", signed.timestamp(), signed.nonce(), signed.signature());
        assertThatThrownBy(() -> AuthenticationSignature.verifyStop(secret, "POST", AuthenticationSignature.STOP_PATH,
                body, gateway, clock, nonces)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> AuthenticationSignature.verifyStop(secret, "POST", AuthenticationSignature.STOP_PATH,
                body, signed, Clock.offset(clock, Duration.ofSeconds(31)), nonces)).isInstanceOf(SecurityException.class);
        AuthenticationSignature.verifyStop(secret, "POST", AuthenticationSignature.STOP_PATH, body, signed, clock, nonces);
        assertThatThrownBy(() -> AuthenticationSignature.verifyStop(secret, "POST", AuthenticationSignature.STOP_PATH,
                body, signed, clock, nonces)).isInstanceOf(SecurityException.class);
    }
    private final String secret = "test-auth-secret-32-bytes-minimum";
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final Set<String> used = new HashSet<>();
    private final AuthenticationSignature.NonceStore nonces = (caller, nonce, ttl) -> used.add(caller + nonce);

    @Test
    void fixedCallersCanCheckButReplayAndChangedTokenCannot() {
        for (String caller : new String[]{"kb-ai", "kb-gateway"}) {
            var signed = AuthenticationSignature.sign(secret, caller, "target", clock);
            assertThatThrownBy(() -> verify(signed, "replacement", clock)).isInstanceOf(SecurityException.class);
            verify(signed, "target", clock);
            assertThatThrownBy(() -> verify(signed, "target", clock)).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void expiryForgeryWrongOperationAndBodyAreRejected() {
        var signed = AuthenticationSignature.sign(secret, "kb-ai", "target", clock);
        assertThatThrownBy(() -> verify(signed, "target", Clock.offset(clock, Duration.ofSeconds(31))))
                .isInstanceOf(SecurityException.class);
        var forged = new AuthenticationSignature.Headers("kb-ai", signed.timestamp(), signed.nonce(), "forged");
        assertThatThrownBy(() -> verify(forged, "target", clock)).isInstanceOf(SecurityException.class);
        for (String path : new String[]{"/internal/conversations/check", "/internal/auth/restore", "/internal/auth/check?x=1"}) {
            assertThatThrownBy(() -> AuthenticationSignature.verify(secret, "POST", path, null, "target", signed, clock, nonces))
                    .isInstanceOf(SecurityException.class);
        }
        assertThatThrownBy(() -> AuthenticationSignature.verify(secret, "POST", AuthenticationSignature.PATH,
                new byte[]{1}, "target", signed, clock, nonces)).isInstanceOf(SecurityException.class);
        assertThat(used).isEmpty();
    }

    @Test
    void NonceStoreFailureCannotBeInterpretedAsSuccessfulAuthentication() {
        var signed = AuthenticationSignature.sign(secret, "kb-ai", "target", clock);
        assertThatThrownBy(() -> AuthenticationSignature.verify(secret, "POST", AuthenticationSignature.PATH,
                null, "target", signed, clock, (caller, nonce, ttl) -> {
                    throw new IllegalStateException("unavailable");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(AuthenticationSignature.configured("short")).isFalse();
        assertThat(signed.toString()).doesNotContain(signed.signature(), signed.nonce(), "target");
    }

    private void verify(AuthenticationSignature.Headers headers, String token, Clock time) {
        AuthenticationSignature.verify(secret, "POST", AuthenticationSignature.PATH, null, token, headers, time, nonces);
    }
}
