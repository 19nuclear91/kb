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
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalClientTest {

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
                {"data":[{
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
                {"data":[
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
                {"data":[
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
                {"data":[null,42,"invalid",{},
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
                {"data":[{
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
    void rejectedRequestKeepsExistingEmptyResultFallbackWithoutRetryOrServiceToken() {
        RetrievalClient client = client(HttpStatus.UNAUTHORIZED, "{\"code\":500}");

        StepVerifier.create(client.retrieve("query", null))
                .expectNext(List.of())
                .verifyComplete();
        assertThat(request.get().headers()).doesNotContainKey(RetrievalClient.TOKEN_HEADER);
    }

    @Test
    void candidateCountScalesWithTopKInsideJsonBody() throws Exception {
        RetrievalClient client = client(HttpStatus.OK, "{\"data\":[]}", 20);
        StepVerifier.create(client.retrieve("query", "token"))
                .expectNext(List.of()).verifyComplete();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new ObjectMapper().readValue(requestBody.get(), Map.class);
        assertThat(body).containsEntry("query", "query")
                .containsEntry("candidates", 80).containsEntry("topK", 20);
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
            StepVerifier.create(client.retrieve(canary, canary)).expectNext(List.of()).verifyComplete();
            RetrievalClient rejected = client(HttpStatus.BAD_GATEWAY,
                    "{\"error\":\"body=" + canary + "\"}");
            StepVerifier.create(rejected.retrieve(canary, canary))
                    .expectNext(List.of()).verifyComplete();
            assertThat(captured.list).isNotEmpty();
            assertThat(captured.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(canary);
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(captured.list.getFirst().getFormattedMessage())
                    .contains("stage=retrieval", "category=internal", "status=0");
            assertThat(captured.list.getLast().getFormattedMessage())
                    .contains("category=upstream_response", "status=502");
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
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
