package com.zhishiku.kb.common.conversation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class RequestSignatureTest {
    private final String secret = "0123456789abcdef0123456789abcdef";
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final byte[] body = "多轮😀".getBytes(StandardCharsets.UTF_8);

    @Test
    void authenticatesUnicodeAndRejectsReplays() {
        var seen = new HashSet<String>();
        RequestSignature.NonceStore store = (caller, nonce, ttl) -> seen.add(caller + nonce);
        var headers = RequestSignature.sign(secret, "kb-ai", "POST", "/internal/conversations/begin", body, "token",
                clock);
        assertThatCode(() -> verify(headers, body, "token", clock, store)).doesNotThrowAnyException();
        assertThatThrownBy(() -> verify(headers, body, "token", clock, store)).isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsBodyTokenCallerClockAndStorageFailures() {
        var headers = RequestSignature.sign(secret, "kb-ai", "POST", "/internal/conversations/begin", body, "token",
                clock);
        assertThatThrownBy(() -> verify(headers, new byte[0], "token", clock, (a, b, c) -> true)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verify(headers, body, "other", clock, (a, b, c) -> true)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verify(headers, body, "token", Clock.offset(clock, Duration.ofSeconds(61)), (a, b,
                                                                                                              c) -> true)).isInstanceOf(SecurityException.class);
        var wrong = new RequestSignature.Headers("kb-app", headers.timestamp(), headers.nonce(), headers.signature());
        assertThatThrownBy(() -> verify(wrong, body, "token", clock, (a, b, c) -> true)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> verify(headers, body, "token", clock, (a, b, c) -> {
            throw new IllegalStateException("secret");
        }))
                .isInstanceOf(SecurityException.class).hasMessage("内部请求身份无效").hasNoCause();
        assertThatThrownBy(() -> RequestSignature.verify(secret, "kb-ai", "GET", "/internal/conversations/begin",
                body, "token", headers, clock, 60, 180, (a, b, c) -> true)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> RequestSignature.verify(secret, "kb-ai", "POST", "/internal/conversations/finish",
                body, "token", headers, clock, 60, 180, (a, b, c) -> true)).isInstanceOf(SecurityException.class);
    }

    private void verify(RequestSignature.Headers headers, byte[] value, String token, Clock time,
                        RequestSignature.NonceStore store) {
        RequestSignature.verify(secret, "kb-ai", "POST", "/internal/conversations/begin", value, token, headers, time
                , 60, 180, store);
    }

    @Test
    void transportTraceHeadersDoNotParticipateInAuthentication() {
        var signed = RequestSignature.sign(secret, "kb-ai", "POST", "/internal/conversations/begin", body, "token", clock);
        var transport = new HashMap<String, String>();
        transport.put("X-Service-Caller", signed.caller());
        transport.put("X-Service-Timestamp", Long.toString(signed.timestamp()));
        transport.put("X-Service-Nonce", signed.nonce());
        transport.put("X-Service-Signature", signed.signature());
        for (String traceparent : new String[]{"", "00-11111111111111111111111111111111-2222222222222222-01", "untrusted"}) {
            transport.put("traceparent", traceparent);
            var received = new RequestSignature.Headers(transport.get("X-Service-Caller"),
                    Long.parseLong(transport.get("X-Service-Timestamp")), transport.get("X-Service-Nonce"),
                    transport.get("X-Service-Signature"));
            assertThat(received.signature()).isEqualTo(signed.signature());
            assertThatCode(() -> verify(received, body, "token", clock, (caller, nonce, ttl) -> true))
                    .doesNotThrowAnyException();
        }
    }
}
