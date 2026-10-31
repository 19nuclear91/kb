package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.execution.SearchHttpTransport;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.embedding.CallBudget;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchHttpTransportTest {
    private static final URI ENDPOINT = URI.create("https://unused.invalid/test");

    @Test
    void actualResponseBytesAreBoundedEvenWithoutHeaders() {
        for (int maximum : new int[]{1024 * 1024, 256 * 1024}) {
            String content = "{\"padding\":\"" + "x".repeat(maximum - 14) + "\"}";
            assertThat(content.getBytes(StandardCharsets.UTF_8)).hasSize(maximum);
            var budget = new Budget(maximum);
            var accepted = new SearchHttpTransport(request -> response(content));
            assertThat(accepted.post(ENDPOINT, null, Map.of(), budget, 100, 30)).containsKey("padding");
            var input = new ByteArrayInputStream((content + " ".repeat(100)).getBytes(StandardCharsets.UTF_8));
            var rejected = new SearchHttpTransport(request -> new SearchHttpTransport.Response(200, input));
            assertThatThrownBy(() -> rejected.post(ENDPOINT, null, Map.of(), budget, 100, 30))
                    .isInstanceOfSatisfying(SearchStageFailure.class,
                            e -> assertThat(e.reason()).isEqualTo(SearchStageFailure.Reason.PAYLOAD_LIMIT));
            assertThat(input.available()).isEqualTo(99);
        }
    }

    @Test
    void requestIsBoundedBeforeSendingAndUsesTheSmallerTimeout() {
        int maximum = 2 * 1024 * 1024;
        var sent = new AtomicInteger();
        var transport = new SearchHttpTransport(request -> {
            sent.incrementAndGet();
            assertThat(request.timeout().orElseThrow().toMillis()).isEqualTo(1000);
            assertThat(request.bodyPublisher().orElseThrow().contentLength()).isEqualTo(maximum);
            return response("{}");
        });
        var budget = new Budget(100);
        transport.post(ENDPOINT, null, Map.of("padding", "x".repeat(maximum - 14)), budget, maximum, 1);
        assertThat(budget.limited).isEqualTo(1_000_000_000L);
        assertThatThrownBy(() -> transport.post(ENDPOINT, null,
                Map.of("padding", "x".repeat(maximum - 13)), budget, maximum, 1))
                .isInstanceOf(SearchStageFailure.class);
        assertThat(sent.get()).isOne();
    }

    @Test
    void continuousSlowResponseAndLateCancelledReturnAreNotAccepted() {
        var budget = new Budget(1000);
        var closed = new AtomicBoolean();
        InputStream slow = new ByteArrayInputStream("{\"data\":[]}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                budget.clock.addAndGet(1_000_000_000L);
                return super.read(buffer, offset, Math.min(1, length));
            }
            @Override
            public void close() {
                closed.set(true);
            }
        };
        var transport = new SearchHttpTransport(request -> new SearchHttpTransport.Response(200, slow));
        assertThatThrownBy(() -> transport.post(ENDPOINT, null, Map.of(), budget, 100, 30))
                .isInstanceOf(SearchFailure.class);
        assertThat(closed).isTrue();
        var cancelled = new Budget(100);
        var late = new SearchHttpTransport(request -> {
            cancelled.clock.set(Long.MAX_VALUE);
            return response("{}");
        });
        assertThatThrownBy(() -> late.post(ENDPOINT, null, Map.of(), cancelled, 100, 30))
                .isInstanceOf(SearchFailure.class);
    }

    @Test
    void errorsAreNotReadAndMalformedJsonFailsWithFixedCategory() {
        var input = new ByteArrayInputStream("private upstream error".getBytes(StandardCharsets.UTF_8));
        var transport = new SearchHttpTransport(request -> new SearchHttpTransport.Response(503, input));
        assertThatThrownBy(() -> transport.post(ENDPOINT, null, Map.of(), new Budget(100), 100, 30))
                .hasMessage("UPSTREAM");
        assertThat(input.available()).isEqualTo(22);
        for (String value : new String[]{"", "null", "{} {}", "{\"x\":1,\"x\":2}", "[]",
                "{\"x\":" + "[".repeat(70) + "0" + "]".repeat(70) + "}"}) {
            var malformed = new SearchHttpTransport(request -> response(value));
            assertThatThrownBy(() -> malformed.post(ENDPOINT, null, Map.of(), new Budget(1000), 100, 30))
                    .hasMessage("INVALID_RESPONSE");
        }
    }

    private static SearchHttpTransport.Response response(String body) {
        return new SearchHttpTransport.Response(200, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class Budget implements CallBudget {
        private final int maximum;
        private final AtomicLong clock = new AtomicLong();
        private long limited;

        private Budget(int maximum) {
            this.maximum = maximum;
        }
        public long remainingNanos() {
            return Math.max(0, 2_000_000_000L - clock.get());
        }
        public int maxResponseBytes() {
            return maximum;
        }
        public void check() {
            if (remainingNanos() == 0) {
                throw new SearchFailure(SearchFailure.Kind.TIMEOUT);
            }
        }
        public Registration onCancel(Runnable action) {
            return () -> { };
        }
        public Registration limitTo(long nanos) {
            limited = nanos;
            return () -> { };
        }
    }
}
