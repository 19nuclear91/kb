package com.zhishiku.kb.ai.chat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import io.netty.buffer.PooledByteBufAllocator;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalClientTest {

    @Test
    void telemetryClassifiesHttpProtocolDependencyAndTimeoutWithoutSensitiveLabels() {
        var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.OK)) {
                request.set(null);
                var client = client(status, "secret-invalid-body");
                client.setTelemetry(new com.zhishiku.kb.ai.telemetry.DependencyTelemetry(meters));
                StepVerifier.create(client.retrieve("secret-query", "secret-token")).expectError(RetrievalFailure.class).verify();
            }
            for (boolean timeout : new boolean[]{false, true}) {
                var client = new RetrievalClient(WebClient.builder().exchangeFunction(request -> timeout ? Mono.never()
                        : Mono.error(new IllegalStateException("secret-error"))), new AiProperties("http://app.invalid", 5, 1000, 60));
                client.setTelemetry(new com.zhishiku.kb.ai.telemetry.DependencyTelemetry(meters));
                StepVerifier.withVirtualTime(() -> client.retrieve("secret-query", "secret-token"))
                        .thenAwait(Duration.ofMinutes(1)).expectError(RetrievalFailure.class).verify();
            }
            assertThat(meters.get("kb.ai.retrieval.failures").tag("reason", "REJECTED").counter().count()).isEqualTo(2);
            assertThat(meters.get("kb.ai.retrieval.failures").tag("reason", "UNAVAILABLE").counter().count()).isEqualTo(2);
            assertThat(meters.get("kb.ai.retrieval.failures").tag("reason", "PROTOCOL").counter().count()).isEqualTo(1);
            assertThat(meters.get("kb.ai.retrieval.failures").tag("reason", "TIMEOUT").counter().count()).isEqualTo(1);
            assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).singleElement().satisfies(tag -> {
                assertThat(tag.getKey()).isEqualTo("reason");
                assertThat(tag.getValue()).isIn("REJECTED", "UNAVAILABLE", "PROTOCOL", "TIMEOUT");
            }));
        } finally {
            meters.close();
        }
    }

    private final AtomicReference<ClientRequest> request = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @Test
    void completeProvenanceAndCallerTokenArePreserved() {
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[{
                  "docId":5000000001,"docTitle":"架构说明","pageNo":3,"chunkId":5000004001,
                  "content":"分块正文","versionId":5000002001,"versionNo":7,"chunkIdx":4,
                  "headingPath":"设计 / 权限","structureType":"PARAGRAPH",
                  "sourceLineStart":10,"sourceLineEnd":18,
                  "sourceCharStart":120,"sourceCharEnd":340,"sourceLocator":"section:access"
                }]}
                """);

        StepVerifier.create(client.retrieve("query", "caller-token"))
                .expectNext(List.of(new RetrievalClient.Citation(5000000001L, "架构说明", 3,
                        5000004001L, "分块正文", 5000002001L, 7, 4, "设计 / 权限", "PARAGRAPH",
                        10, 18, 120, 340, "section:access", false)))
                .verifyComplete();

        assertThat(request.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(request.get().url().getPath()).isEqualTo("/api/search");
        assertThat(request.get().url().getRawQuery()).isNull();
        assertThat(request.get().headers().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(request.get().headers().getFirst(RetrievalClient.TOKEN_HEADER))
                .isEqualTo("caller-token");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = new ObjectMapper().readValue(requestBody.get(), Map.class);
            assertThat(body).containsEntry("query", "query")
                    .containsEntry("topK", 5)
                    .containsEntry("candidates", 50).hasSize(3);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void historicalNullSourcesRemainUnknownWithVersionIdentityIntact() {
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[{
                  "docId":10,"docTitle":null,"pageNo":null,"chunkId":20,"content":null,
                  "versionId":30,"versionNo":null,"chunkIdx":null,
                  "headingPath":null,"structureType":null,"sourceLineStart":null,
                  "sourceLineEnd":null,"sourceCharStart":null,"sourceCharEnd":null,"sourceLocator":null
                }]}
                """);

        StepVerifier.create(client.retrieve("query", null))
                .expectNext(List.of(new RetrievalClient.Citation(10L, null, null, 20L,
                        null, 30L, null, null, null, null, null, null, null, null, null, false)))
                .verifyComplete();
        assertThat(request.get().headers()).doesNotContainKey(RetrievalClient.TOKEN_HEADER);
    }

    @Test
    void missingAndNullVersionsRemainUsableWithoutInventingIdentity() {
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[
                  {"docId":10,"docTitle":"旧响应","chunkId":20,"content":"旧正文"},
                  {"docId":11,"docTitle":"兼容响应","chunkId":21,"content":"正文","versionId":null}
                ]}
                """);

        StepVerifier.create(client.retrieve("query", "token"))
                .expectNext(List.of(
                        new RetrievalClient.Citation(10L, "旧响应", null, 20L, "旧正文"),
                        new RetrievalClient.Citation(11L, "兼容响应", null, 21L, "正文")))
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidIdentities")
    void damagedIdentityIsDroppedWithoutLosingOtherRows(String field, String value) {
        String docId = field.equals("docId") ? value : "10";
        String chunkId = field.equals("chunkId") ? value : "20";
        String versionId = field.equals("versionId") ? value : "30";
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[
                  {"docId":1,"chunkId":2,"content":"before"},
                  {"docId":%s,"chunkId":%s,"versionId":%s,"content":"damaged"},
                  {"docId":3,"chunkId":4,"content":"after"}
                ]}
                """.formatted(docId, chunkId, versionId));

        StepVerifier.create(client.retrieve("query", "token"))
                .assertNext(citations -> assertThat(citations)
                        .extracting(RetrievalClient.Citation::content).containsExactly("before", "after"))
                .verifyComplete();
    }

    static Stream<Arguments> invalidIdentities() {
        Stream<Arguments> malformed = Stream.of("docId", "chunkId", "versionId")
                .flatMap(field -> Stream.of("0", "-1", "1.5", "1.0", "1e0", "\"30\"", "true", "{}", "[]",
                                "9223372036854775808", "-9223372036854775809")
                        .map(value -> Arguments.of(field, value)));
        return Stream.concat(malformed, Stream.of(Arguments.of("docId", "null"),
                Arguments.of("chunkId", "null")));
    }

    @Test
    void incompleteRowsAndNonObjectsDoNotDiscardValidLongMaxIdentity() {
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[null,42,"invalid",{},
                  {"docId":10,"content":"missing chunk"},
                  {"chunkId":20,"content":"missing doc"},
                  {"docId":9223372036854775807,"chunkId":9223372036854775807,
                   "versionId":9223372036854775807,"content":"valid"}
                ]}
                """);

        StepVerifier.create(client.retrieve("query", "token"))
                .expectNext(List.of(new RetrievalClient.Citation(Long.MAX_VALUE, null, null,
                        Long.MAX_VALUE, "valid", Long.MAX_VALUE, null, null, null, null,
                        null, null, null, null, null, false)))
                .verifyComplete();
    }

    @Test
    void optionalFieldsDoNotTruncateNumbersOrConvertObjectsToFakeSources() {
        RetrievalClient client = client(HttpStatus.OK, """
                {"code":200,"data":[{
                  "docId":10,"chunkId":20,"versionId":30,"versionNo":1.5,"chunkIdx":2147483648,
                  "pageNo":{},"headingPath":42,"structureType":[],"sourceLineStart":true,
                  "sourceLineEnd":"10","sourceCharStart":0,"sourceCharEnd":2147483647,"sourceLocator":null
                }]}
                """);

        StepVerifier.create(client.retrieve("query", "token"))
                .expectNext(List.of(new RetrievalClient.Citation(10L, null, null, 20L, null,
                        30L, null, null, null, null, null, null, 0, Integer.MAX_VALUE, null, false)))
                .verifyComplete();
    }

    @Test
    void rejectedRequestFailsWithoutRetryOrServiceToken() {
        RetrievalClient client = client(HttpStatus.UNAUTHORIZED, "{\"code\":500}");

        StepVerifier.create(client.retrieve("query", null))
                .expectError(RetrievalFailure.class).verify();
        assertThat(request.get().headers()).doesNotContainKey(RetrievalClient.TOKEN_HEADER);
    }

    @Test
    void candidateCountScalesWithTopKInsideJsonBody() throws Exception {
        RetrievalClient client = client(HttpStatus.OK, "{\"code\":200,\"data\":[]}", 100);
        StepVerifier.create(client.retrieve("query", "token"))
                .expectNext(List.of()).verifyComplete();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(requestBody.get(), Map.class);
        assertThat(body).containsEntry("query", "query")
                .containsEntry("candidates", 200).containsEntry("topK", 100);
        assertThat(request.get().url().getRawQuery()).isNull();
    }

    @Test
    void failureLogsNeverIncludeQuestionUriBodyOrExceptionChain() {
        String canary = "QUERY_CANARY_938";
        Logger logger = (Logger) LoggerFactory.getLogger(RetrievalClient.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            WebClient.Builder builder = WebClient.builder().exchangeFunction(incoming ->
                    Mono.error(new IllegalStateException("URI /api/search?q=" + canary
                            + " upstream body=" + canary,
                            new IllegalArgumentException("cause=" + canary))));
            RetrievalClient client = new RetrievalClient(builder,
                    new AiProperties("http://app.invalid", 5, 15_000, 30));
            StepVerifier.create(client.retrieve(canary, canary)).expectError(RetrievalFailure.class).verify();
            RetrievalClient rejected = client(HttpStatus.BAD_GATEWAY,
                    "{\"error\":\"body=" + canary + "\"}");
            StepVerifier.create(rejected.retrieve(canary, canary))
                    .expectError(RetrievalFailure.class).verify();
            assertThat(captured.list).isNotEmpty();
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(canary);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(captured.list.getFirst().getFormattedMessage())
                    .contains("stage=retrieval", "category=DEPENDENCY");
            assertThat(captured.list.getLast().getFormattedMessage())
                    .contains("category=HTTP");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void emptyOrBrokenResponsesFailButSuccessfulEmptyListIsEmitted() {
        for (String body : List.of("", "null", "{}", "{\"data\":[]}", "{\"code\":500,\"data\":[]}",
                "{\"code\":200,\"data\":null}", "{\"code\":200,\"data\":[{}]}", "{\"code\":200,\"data\":[null]}")) {
            request.set(null);
            StepVerifier.create(client(HttpStatus.OK, body).retrieve("q", "token"))
                    .expectError(RetrievalFailure.class).verify();
        }
        request.set(null);
        StepVerifier.create(client(HttpStatus.NO_CONTENT, "").retrieve("q", "token"))
                .expectError(RetrievalFailure.class).verify();
        request.set(null);
        StepVerifier.create(client(HttpStatus.OK, "{\"code\":200,\"data\":[]}").retrieve("q", "token"))
                .expectNext(List.of()).verifyComplete();
    }

    @Test
    void fourMiBLimitIsAppliedBeforeDecodingAndHasNoRetry() {
        String body = "{\"code\":200,\"data\":[],\"padding\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}";
        StepVerifier.create(client(HttpStatus.OK, body).retrieve("q", "token"))
                .expectErrorSatisfies(error -> assertThat(((RetrievalFailure) error).kind())
                        .isEqualTo(RetrievalFailure.Kind.PAYLOAD_LIMIT)).verify();
    }

    @Test
    void oversizedChunkedResponseIsCancelledOnceWithoutCleanupResubscription() {
        var subscriptions = new AtomicInteger();
        var cancels = new AtomicInteger();
        var consumed = new AtomicInteger();
        var buffers = new ArrayList<NettyDataBuffer>();
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        Flux<DataBuffer> body = Flux.range(0, 100).map(index -> {
            var buffer = factory.allocateBuffer(256);
            buffer.write(new byte[256]);
            buffers.add(buffer);
            consumed.addAndGet(256);
            return (DataBuffer) buffer;
        }).doOnSubscribe(ignored -> subscriptions.incrementAndGet()).doOnCancel(cancels::incrementAndGet);
        StepVerifier.create(streamingClient(HttpStatus.OK, body, 1024).retrieve("q", "token"))
                .expectErrorSatisfies(error -> assertThat(((RetrievalFailure) error).kind())
                        .isEqualTo(RetrievalFailure.Kind.PAYLOAD_LIMIT)).verify();
        assertThat(subscriptions.get()).isOne();
        assertThat(cancels.get()).isOne();
        assertThat(consumed.get()).isEqualTo(1280);
        assertThat(buffers).allSatisfy(buffer -> assertThat(buffer.getNativeBuffer().refCnt()).isZero());
    }

    @Test
    void httpRejectionsCancelWithoutRequestingAnyErrorBody() {
        for (var status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE)) {
            var subscriptions = new AtomicInteger();
            var cancels = new AtomicInteger();
            var requested = new AtomicBoolean();
            Flux<DataBuffer> body = Flux.<DataBuffer>never().doOnSubscribe(ignored -> subscriptions.incrementAndGet())
                    .doOnRequest(ignored -> requested.set(true)).doOnCancel(cancels::incrementAndGet);
            StepVerifier.create(streamingClient(status, body, 1024).retrieve("q", "token"))
                    .expectErrorSatisfies(error -> assertThat(((RetrievalFailure) error).kind())
                            .isEqualTo(RetrievalFailure.Kind.HTTP)).verify();
            assertThat(subscriptions.get()).isOne();
            assertThat(cancels.get()).isOne();
            assertThat(requested).isFalse();
        }
    }

    @Test
    void exactByteBoundaryCompletesOnceAndCancellationReleasesAccumulatedBuffers() {
        var subscriptions = new AtomicInteger();
        var factory = new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT);
        String json = "{\"code\":200,\"data\":[]}";
        byte[] bytes = (json + " ".repeat(1024 - json.length())).getBytes(StandardCharsets.UTF_8);
        var buffers = new ArrayList<NettyDataBuffer>();
        Flux<DataBuffer> body = Flux.range(0, 4).map(index -> {
            var buffer = factory.allocateBuffer(256);
            buffer.write(bytes, index * 256, 256);
            buffers.add(buffer);
            return (DataBuffer) buffer;
        }).doOnSubscribe(ignored -> subscriptions.incrementAndGet());
        StepVerifier.create(streamingClient(HttpStatus.OK, body, 1024).retrieve("q", "token"))
                .expectNext(List.of()).verifyComplete();
        assertThat(subscriptions.get()).isOne();
        assertThat(buffers).allSatisfy(buffer -> assertThat(buffer.getNativeBuffer().refCnt()).isZero());

        var retained = factory.allocateBuffer(10);
        retained.write(new byte[10]);
        var cancels = new AtomicInteger();
        var source = TestPublisher.<DataBuffer>create();
        Flux<DataBuffer> pending = source.flux()
                .doOnSubscribe(ignored -> subscriptions.incrementAndGet()).doOnCancel(cancels::incrementAndGet);
        StepVerifier.create(streamingClient(HttpStatus.OK, pending, 1024).retrieve("q", "token"))
                .then(() -> source.assertSubscribers(1).next(retained))
                .thenCancel().verify();
        assertThat(subscriptions.get()).isEqualTo(2);
        assertThat(cancels.get()).isOne();
        assertThat(retained.getNativeBuffer().refCnt()).isZero();
    }

    private RetrievalClient streamingClient(HttpStatus status, Flux<DataBuffer> body, int maximum) {
        var builder = WebClient.builder().exchangeFunction(incoming -> Mono.just(ClientResponse.create(status).body(body).build()));
        return new RetrievalClient(builder, new AiProperties("http://app.invalid", 5, 15000, 60),
                new RetrievalProperties(10000, maximum));
    }

    @Test
    void retrievalTimeoutIsTenSecondsAndCancellationDoesNotProduceEmptyResults() {
        var calls = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var builder = WebClient.builder().exchangeFunction(incoming -> {
            calls.incrementAndGet();
            return Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true));
        });
        var client = new RetrievalClient(builder, new AiProperties("http://app.invalid", 5, 15000, 60));
        StepVerifier.withVirtualTime(() -> client.retrieve("q", "t"))
                .expectSubscription().thenAwait(Duration.ofSeconds(10))
                .expectErrorSatisfies(error -> assertThat(((RetrievalFailure) error).kind())
                        .isEqualTo(RetrievalFailure.Kind.TIMEOUT)).verify();
        assertThat(cancelled).isTrue();
        assertThat(calls.get()).isOne();
        cancelled.set(false);
        StepVerifier.create(client.retrieve("q", "t")).thenCancel().verify();
        assertThat(cancelled).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }

    private RetrievalClient client(HttpStatus status, String json) {
        return client(status, json, 5);
    }

    private RetrievalClient client(HttpStatus status, String json, int topK) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(incoming -> {
            assertThat(request.compareAndSet(null, incoming)).isTrue();
            MockClientHttpRequest output = new MockClientHttpRequest(incoming.method(), incoming.url());
            return incoming.writeTo(output, ExchangeStrategies.withDefaults())
                    .then(Mono.defer(() -> output.getBodyAsString().map(body -> {
                        requestBody.set(body);
                        return ClientResponse.create(status)
                                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                .body(json)
                                .build();
                    })));
        });
        return new RetrievalClient(builder, new AiProperties("http://app.invalid", topK, 15_000, 30));
    }
}
