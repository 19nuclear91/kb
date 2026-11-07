package com.zhishiku.kb.worker.embedding.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerEmbeddingTransportTest {
    @Test
    void unexpectedAsyncFailureRemainsUnknownWithoutSupplierDetails() {
        var session = new MemoryEmbeddingSession(200, new byte[0], Map.of()) {
            @Override
            public CompletableFuture<HttpResponse<InputStream>> send(HttpRequest request,
                                                                      HttpResponse.BodyHandler<InputStream> handler) {
                return CompletableFuture.failedFuture(new IllegalStateException("private endpoint and body"));
            }
        };
        assertThatThrownBy(() -> post(session, 1024, 64)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Worker embedding transport unavailable").hasNoCause();
        assertThat(session.shutdown).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 302, 400, 401, 403, 408, 413, 429, 500, 503})
    void non200NeverRequestsBodyOrRetries(int status) {
        var session = new MemoryEmbeddingSession(status, "private document bearer value".getBytes(StandardCharsets.UTF_8),
                Map.of("Location", List.of("https://other.invalid"), "Retry-After", List.of("10")));
        assertThatThrownBy(() -> post(session, 1024, 64))
                .isInstanceOf(WorkerEmbeddingFailure.class)
                .hasMessage(WorkerEmbeddingFailure.http(status).getMessage()).hasNoCause();
        assertThat(session.bodyRequests.get()).isZero();
        assertThat(session.cancellations.get()).isOne();
        assertThat(session.requests).hasSize(1);
        assertThat(session.shutdown).isTrue();
    }

    @Test
    void exactResponseLimitSucceedsAndOneExtraByteFailsWithoutLengthHeader() {
        byte[] bytes = "{\"unknown\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(post(new MemoryEmbeddingSession(200, bytes, Map.of()), bytes.length, 64))
                .containsEntry("unknown", "value");
        assertThatThrownBy(() -> post(new MemoryEmbeddingSession(200, bytes, Map.of()), bytes.length - 1, 64))
                .hasMessage("Worker embedding: RESPONSE_LIMIT");
    }

    @Test
    void declaredSizeIsCheckedBeforeBodyAndUnderstatementCannotBypassActualLimit() {
        var declared = new MemoryEmbeddingSession(200, new byte[0], Map.of("Content-Length", List.of("1000")));
        assertThatThrownBy(() -> post(declared, 20, 64)).hasMessage("Worker embedding: RESPONSE_LIMIT");
        assertThat(declared.bodyRequests.get()).isZero();
        var understated = new MemoryEmbeddingSession(200, ("{\"extra\":\"" + "a".repeat(100) + "\"}")
                .getBytes(StandardCharsets.UTF_8), Map.of("Content-Length", List.of("1")));
        assertThatThrownBy(() -> post(understated, 20, 64)).hasMessage("Worker embedding: RESPONSE_LIMIT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{}{}", "{\"data\":[],\"data\":[]}",
            "{\"data\":[{\"index\":0,\"index\":1}]}", "{\"a\":[[[[1]]]]}"})
    void invalidJsonRejectsEntireResponse(String input) {
        var session = new MemoryEmbeddingSession(200, input.getBytes(StandardCharsets.UTF_8), Map.of());
        assertThatThrownBy(() -> post(session, 1024, 4)).hasMessage("Worker embedding: INVALID_RESPONSE");
    }

    @Test
    void contentEncodingAndInvalidUtf8AreRejected() {
        var compressed = new MemoryEmbeddingSession(200, new byte[0], Map.of("Content-Encoding", List.of("gzip")));
        assertThatThrownBy(() -> post(compressed, 1024, 64)).hasMessage("Worker embedding: INVALID_RESPONSE");
        assertThat(compressed.bodyRequests.get()).isZero();
        for (byte[] bytes : List.of(new byte[]{(byte) 0xc0, (byte) 0x80}, new byte[]{0, 123, 0, 125})) {
            assertThatThrownBy(() -> post(new MemoryEmbeddingSession(200, bytes, Map.of()), 1024, 64))
                    .hasMessage("Worker embedding: INVALID_RESPONSE");
        }
    }

    private static Map<String, Object> post(MemoryEmbeddingSession session, int responseBytes, int depth) {
        var settings = new WorkerEmbeddingSettings(1048576, responseBytes, depth, 300, 10);
        var transport = new WorkerEmbeddingTransport(settings, new WorkerEmbeddingJson(settings), () -> session);
        return transport.forProfile(EmbeddingTestSupport.definition(16))
                .post(EmbeddingTestSupport.body(List.of("text")), EmbeddingTestSupport.budget());
    }
}
