package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 只调用原身份、本人会话及问答接口，所有身份预检成功后才允许写入。 */
public final class BusinessClient {
    public record Citation(DataModel.Source source, boolean contextTruncated) {
    }

    public record Turn(long conversationId, String turnId, String answer, List<Citation> citations,
                       boolean replayed, String status, EvaluationFailure.Code error) {
        public Turn {
            citations = List.copyOf(citations);
        }
    }

    public record Saved(String turnId, long turnSeq, String question, String answer, String status,
                        String visibility, DataModel.AnswerKind kind, String settlementState,
                        long savedCodepoints, List<Citation> citations, DataModel.Usage usage) {
        public Saved {
            citations = List.copyOf(citations);
        }
    }

    private final HttpExchange http;
    private final CollectionConfig config;
    private final CollectionConfig.Credentials credentials;
    private final LongSupplier clock;
    private boolean identitiesReady;

    public BusinessClient(Transport transport, CollectionConfig config, CollectionConfig.Credentials credentials, LongSupplier clock) {
        http = new HttpExchange(transport);
        this.config = config;
        this.credentials = credentials;
        this.clock = clock;
    }

    public void preflight() {
        for (String alias : credentials.tokens().keySet().stream().sorted().toList()) {
            JsonNode root = json(request(alias, "/api/auth/me", "GET", new byte[0]), Set.of(200),
                    new Deadline(clock, config.caseTimeoutMs()));
            JsonNode user = root.path("data").path("user");
            JsonNode change = root.path("data").path("mustChangePassword");
            if (integer(root, "code", 0) != 200 || integer(user, "userId", 1) != config.identities().get(alias).expectedUserId()
                    || !user.path("enabled").isBoolean() || !user.path("enabled").booleanValue()
                    || !change.isBoolean() || change.booleanValue()) {
                throw new EvaluationFailure(EvaluationFailure.Code.IDENTITY);
            }
        }
        identitiesReady = true;
    }

    public long create(String alias, String requestId, Deadline deadline) {
        requireReady();
        JsonNode root = json(request(alias, "/api/conversations", "POST",
                StrictJson.bytes(Map.of("requestId", Checks.uuid(requestId)))), Set.of(200, 201), deadline);
        JsonNode conversation = root.path("conversation");
        if (!root.path("replayed").isBoolean() || root.path("replayed").booleanValue()
                || integer(conversation, "lastTurnSeq", 0) != 0 || !conversation.path("activeTurnId").isNull()) {
            throw protocol();
        }
        return integer(conversation, "id", 1);
    }

    public Turn generate(String alias, long conversationId, String requestId, String question, Deadline deadline) {
        return generate(alias, conversationId, requestId, question, deadline, turnId -> {
        });
    }

    public Turn generate(String alias, long conversationId, String requestId, String question, Deadline deadline,
                         Consumer<String> confirmedTurn) {
        requireReady();
        byte[] body = StrictJson.bytes(Map.of("question", question, "requestId", requestId, "expectedLastTurnSeq", 0));
        return http.execute(request(alias, "/api/ai/conversations/" + conversationId + "/turns", "POST", body), deadline,
                (response, remaining) -> {
                    String type = HttpExchange.contentType(response, Set.of(200), Set.of("application/json", "text/event-stream"));
                    long responseConversation = headerId(response, "X-Conversation-Id");
                    String turnId = header(response, "X-Turn-Id");
                    Checks.uuid(turnId);
                    if (conversationId != responseConversation) {
                        throw protocol();
                    }
                    // 响应头确认的轮次先保存；正文断连不应丢失可用于只读核对的身份。
                    confirmedTurn.accept(turnId);
                    byte[] bytes = StrictJson.bounded(response.body(), OutputPublisher.CAPTURE_LIMIT, remaining::check);
                    if ("application/json".equals(type)) {
                        JsonNode replay = StrictJson.parse(bytes);
                        if (!replay.path("replayed").isBoolean() || !replay.path("replayed").booleanValue()
                                || integer(replay, "conversationId", 1) != conversationId
                                || !turnId.equals(text(replay, "turnId")) || integer(replay, "turnSeq", 1) != 1) {
                            throw protocol();
                        }
                        return new Turn(conversationId, turnId, null, List.of(), true, text(replay, "status"),
                                EvaluationFailure.Code.INCOMPLETE);
                    }
                    return SseAnswers.parse(bytes, conversationId, turnId, remaining);
                });
    }

    public Saved saved(String alias, long conversation, String turn, Deadline deadline) {
        JsonNode root = json(request(alias, "/api/conversations/" + conversation + "/turns/" + Checks.uuid(turn),
                "GET", new byte[0]), Set.of(200), deadline);
        if (!turn.equals(text(root, "id"))) {
            throw protocol();
        }
        String answer = root.path("answer").isNull() ? null : text(root, "answer");
        DataModel.AnswerKind kind;
        try {
            kind = DataModel.AnswerKind.valueOf(text(root, "answerKind"));
        } catch (IllegalArgumentException failure) {
            throw protocol();
        }
        return new Saved(turn, integer(root, "turnSeq", 1), text(root, "question"), answer, text(root, "status"),
                text(root, "visibility"), kind, root.path("settlementState").isNull() ? null : text(root, "settlementState"),
                integer(root, "savedCodepoints", 0), citations(root.path("citations")),
                new DataModel.Usage(nullableLong(root, "promptTokens"), nullableLong(root, "completionTokens")));
    }

    private JsonNode json(Transport.Request request, Set<Integer> statuses, Deadline deadline) {
        return http.execute(request, deadline, (response, remaining) -> {
            HttpExchange.contentType(response, statuses, Set.of("application/json"));
            return StrictJson.parse(StrictJson.bounded(response.body(), OutputPublisher.CAPTURE_LIMIT, remaining::check));
        });
    }

    private Transport.Request request(String alias, String path, String method, byte[] body) {
        String token = credentials.tokens().get(alias);
        if (token == null) {
            throw new EvaluationFailure(EvaluationFailure.Code.IDENTITY);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("satoken", token);
        headers.put("Accept", "application/json, text/event-stream");
        headers.put("Accept-Encoding", "identity");
        if ("POST".equals(method)) {
            headers.put("Content-Type", "application/json; charset=UTF-8");
        }
        return new Transport.Request(Endpoint.api(config.endpoint(), path), method, headers, body);
    }

    private void requireReady() {
        if (!identitiesReady) {
            throw new EvaluationFailure(EvaluationFailure.Code.IDENTITY);
        }
    }

    public static long integer(JsonNode node, String field, long minimum) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) {
            throw protocol();
        }
        return value.longValue();
    }

    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw protocol();
        }
        Checks.unicode(value.textValue());
        return value.textValue();
    }

    private static Long nullableLong(JsonNode node, String field) {
        return node.path(field).isNull() ? null : integer(node, field, 0);
    }

    public static Citation citation(JsonNode node) {
        if (!node.path("contextTruncated").isBoolean()) {
            throw protocol();
        }
        return new Citation(new DataModel.Source(integer(node, "documentId", 1), integer(node, "versionId", 1),
                integer(node, "chunkId", 1)), node.path("contextTruncated").booleanValue());
    }

    private static List<Citation> citations(JsonNode node) {
        if (!node.isArray()) {
            throw protocol();
        }
        List<Citation> result = new ArrayList<>();
        Set<DataModel.Source> seen = new HashSet<>();
        for (JsonNode item : node) {
            Citation citation = citation(item);
            if (!seen.add(citation.source())) {
                throw protocol();
            }
            result.add(citation);
        }
        return result;
    }

    private static String header(Transport.Response response, String name) {
        List<String> values = response.headers().allValues(name);
        if (values.size() != 1) {
            throw protocol();
        }
        return values.getFirst();
    }

    private static long headerId(Transport.Response response, String name) {
        try {
            String value = header(response, name);
            if (!value.matches("[1-9][0-9]{0,18}")) {
                throw protocol();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw protocol();
        }
    }

    static EvaluationFailure protocol() {
        return new EvaluationFailure(EvaluationFailure.Code.PROTOCOL);
    }
}
