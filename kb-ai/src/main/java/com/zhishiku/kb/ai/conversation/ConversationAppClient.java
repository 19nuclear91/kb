package com.zhishiku.kb.ai.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.ai.chat.AiProperties;
import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.AccountRevokedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无数据库的签名客户端；固定写入可以重试，begin 不自动重试。
 */
@Component
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationAppClient {
    private final WebClient client;
    private final ConversationProperties properties;
    private final ObjectMapper json;

    public ConversationAppClient(WebClient.Builder builder, AiProperties ai, ConversationProperties properties,
                                 ObjectMapper json) {
        client =
                builder.baseUrl(ai.appBaseUrl()).codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8_000_000)).build();
        this.properties = properties;
        this.json = json;
    }

    public Mono<BeginResult> check(long id, String question, String request, long expected, String token) {
        return call("check", beginInput(id, question, request, expected), token, false).map(value -> convert(value,
                BeginResult.class));
    }

    public Mono<BeginResult> begin(long id, String question, String request, long expected, String token) {
        return call("begin", beginInput(id, question, request, expected), token, false).map(value -> convert(value,
                BeginResult.class));
    }

    public Mono<List<HistoryTurn>> prepare(long id, String token) {
        return call("prepare", Map.of("conversationId", id), token, false)
                .map(value -> json.convertValue(value, json.getTypeFactory().constructCollectionType(List.class,
                        HistoryTurn.class)));
    }

    public Mono<Void> seal(ExecutionKey key, String kind, List<HistoryTurn> parents,
                           List<RetrievalClient.Citation> citations, String token) {
        return call("seal", Map.of("execution", key, "kind", kind, "parents",
                parents.stream().map(HistoryTurn::id).toList(),
                "citations", citations.stream().map(ConversationAppClient::citation).toList()), token, true).then();
    }

    public Mono<Long> append(ExecutionKey key, long sequence, String text) {
        return call("append", Map.of("execution", key, "sequence", sequence, "text", text), null, true).map(value -> {
            if (!value.path("lastBatchSeq").canConvertToLong()) throw unavailable();
            return value.path("lastBatchSeq").longValue();
        });
    }

    public Mono<Void> renew(ExecutionKey key) {
        return call("renew", Map.of("execution", key), null, true).then();
    }

    public Mono<Void> reserve(ExecutionKey key, long department, String lease, String event, long tokens) {
        return call("reserve", Map.of("execution", key, "departmentId", department, "leaseId", lease, "eventId",
                event, "reservedTokens", tokens), null, true).then();
    }

    public Mono<Void> dispatch(ExecutionKey key) {
        return call("dispatch", Map.of("execution", key), null, true).then();
    }

    public Mono<Void> acknowledge(SettlementIntent intent) {
        return call("ack", Map.of("turnId", intent.turnId(), "intentDigest", intent.intentDigest()), null, true)
                .flatMap(value -> value.path("accepted").asBoolean() ? Mono.empty() : Mono.error(unavailable()));
    }

    public Mono<SettlementIntent> finish(ExecutionKey key, String status, long position, boolean confirmed,
                                         Long prompt, Long completion) {
        return finish(key, status, position, confirmed, prompt, completion, null);
    }

    public Mono<SettlementIntent> finishRevoked(ExecutionKey key, long position, boolean confirmed, Long prompt, Long completion) {
        return finish(key, "CANCELLED", position, confirmed, prompt, completion, "ACCOUNT_REVOKED");
    }

    private Mono<SettlementIntent> finish(ExecutionKey key, String status, long position, boolean confirmed,
                                          Long prompt, Long completion, String cancellationReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("execution", key);
        body.put("status", status);
        body.put("lastBatchSeq", position);
        body.put("finalUsage", confirmed);
        body.put("promptTokens", prompt);
        body.put("completionTokens", completion);
        if (cancellationReason != null) {
            body.put("cancellationReason", cancellationReason);
        }
        return call("finish", body, null, true).flatMap(value -> value.has("turnId") ? Mono.just(convert(value,
                SettlementIntent.class)) : Mono.empty());
    }

    public Mono<JsonNode> call(String operation, Object input, String token, boolean retry) {
        String path = "/internal/conversations/" + operation;
        Mono<JsonNode> request = Mono.defer(() -> {
            byte[] body;
            try {
                body = json.writeValueAsBytes(input);
            } catch (Exception error) {
                return Mono.error(unavailable());
            }
            var headers = RequestSignature.sign(properties.getSecret(), "kb-ai", "POST", path, body, token,
                    Clock.systemUTC());
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).headers(h -> {
                h.set("X-Service-Caller", headers.caller());
                h.set("X-Service-Timestamp", Long.toString(headers.timestamp()));
                h.set("X-Service-Nonce", headers.nonce());
                h.set("X-Service-Signature", headers.signature());
                if (token != null) h.set("satoken", token);
            }).bodyValue(body).exchangeToMono(response -> {
                if (response.statusCode().isError()) {
                    int status = response.statusCode().value();
                    RuntimeException rejected = AccountRevokedException.confirmed(status,
                            response.headers().header(AccountRevokedException.HEADER))
                            ? new AccountRevokedException(status)
                            : new ResponseStatusException(response.statusCode(), "会话请求未被接受");
                    return response.releaseBody().then(Mono.error(rejected));
                }
                return response.bodyToMono(JsonNode.class).switchIfEmpty(Mono.error(unavailable()));
            }).timeout(Duration.ofSeconds(5)).onErrorMap(error -> error instanceof ResponseStatusException
                    || error instanceof AccountRevokedException ? error : unavailable());
        });
        return retry ? request.retryWhen(Retry.fixedDelay(1, Duration.ofMillis(100))
                .filter(error -> error instanceof ResponseStatusException status && status.getStatusCode().is5xxServerError())
                .onRetryExhaustedThrow((spec, signal) -> signal.failure())) : request;
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return json.convertValue(value, type);
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private static Map<String, Object> beginInput(long id, String question, String request, long expected) {
        return Map.of("conversationId", id, "question", question, "requestId", request, "expectedLastTurnSeq",
                expected);
    }

    public static Map<String, Object> citation(RetrievalClient.Citation c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("documentId", c.docId());
        map.put("versionId", c.versionId());
        map.put("chunkId", c.chunkId());
        map.put("title", c.docTitle() == null ? "" : c.docTitle());
        map.put("versionNo", c.versionNo());
        map.put("pageNo", c.pageNo());
        map.put("headingPath", c.headingPath());
        map.put("structureType", c.structureType());
        map.put("sourceLocator", c.sourceLocator());
        map.put("sourceLineStart", c.sourceLineStart());
        map.put("sourceLineEnd", c.sourceLineEnd());
        map.put("sourceCharStart", c.sourceCharStart());
        map.put("sourceCharEnd", c.sourceCharEnd());
        map.put("contextTruncated", c.contextTruncated());
        return map;
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会话服务暂不可用");
    }
}

