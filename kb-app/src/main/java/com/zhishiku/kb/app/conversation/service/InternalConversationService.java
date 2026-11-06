package com.zhishiku.kb.app.conversation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.model.ExecutionKey;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内部协议只在签名通过后解析，所有用户历史操作仍检查当前会话。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class InternalConversationService {
    private final InternalRequestAuthenticator authentication;
    private final ConversationSessionService sessions;
    private final ConversationService conversations;
    private final TurnExecutionService execution;
    private final ObjectMapper json;

    public InternalConversationService(InternalRequestAuthenticator authentication, ConversationSessionService sessions,
                                       ConversationService conversations, TurnExecutionService execution,
                                       ObjectMapper json) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.conversations = conversations;
        this.execution = execution;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(64000).maxNumberLength(20).build());
    }

    public Object invoke(String operation, String method, String path, byte[] body, String token,
                         RequestSignature.Headers headers) {
        authentication.verify(method, path, body, token, headers);
        if (body.length > 2_000_000) throw ConversationService.invalid();
        JsonNode input;
        try {
            input = json.readTree(body);
        } catch (Exception error) {
            throw ConversationService.invalid();
        }
        if (input == null || !input.isObject()) throw ConversationService.invalid();
        return switch (operation) {
            case "ack" -> {
                fields(input, Set.of("turnId", "intentDigest"));
                yield Map.of("accepted", execution.ackSettlement(convert(input.get("turnId"), UUID.class), text(input
                        , "intentDigest")));
            }
            case "check", "begin" -> {
                fields(input, Set.of("conversationId", "question", "requestId", "expectedLastTurnSeq"));
                var session = sessions.session(token);
                long user = session.userId();
                long id = number(input, "conversationId");
                String question = text(input, "question"), request = text(input, "requestId");
                long expected = number(input, "expectedLastTurnSeq");
                var result = "begin".equals(operation) ? execution.begin(user, session.authEpoch(), id, question, request, expected) :
                        execution.check(user, id, question, request, expected);
                yield result == null ? Map.of("replayed", false) : result;
            }
            case "prepare" -> {
                fields(input, Set.of("conversationId"));
                yield conversations.prepareContext(sessions.user(token), number(input, "conversationId"));
            }
            case "seal" -> {
                fields(input, Set.of("execution", "kind", "parents", "citations"));
                var parents = new ArrayList<UUID>();
                var citations = new ArrayList<CitationView>();
                if (!input.path("parents").isArray() || input.path("parents").size() > 32
                        || !input.path("citations").isArray() || input.path("citations").size() > 1024)
                    throw ConversationService.invalid();
                for (var value : input.path("parents")) parents.add(convert(value, UUID.class));
                for (var value : input.path("citations")) citations.add(convert(value, CitationView.class));
                execution.seal(sessions.user(token), key(input), text(input, "kind"), parents, citations);
                yield Map.of("accepted", true);
            }
            case "append" -> {
                fields(input, Set.of("execution", "sequence", "text"));
                yield Map.of("lastBatchSeq", execution.append(key(input), number(input, "sequence"), text(input,
                        "text")));
            }
            case "renew" -> {
                fields(input, Set.of("execution"));
                execution.renew(key(input));
                yield Map.of("accepted", true);
            }
            case "reserve" -> {
                fields(input, Set.of("execution", "departmentId", "leaseId", "eventId", "reservedTokens"));
                execution.reserve(key(input), number(input, "departmentId"), convert(input.get("leaseId"), UUID.class),
                        convert(input.get("eventId"), UUID.class), number(input, "reservedTokens"));
                yield Map.of("accepted", true);
            }
            case "dispatch" -> {
                fields(input, Set.of("execution"));
                execution.dispatch(key(input));
                yield Map.of("accepted", true);
            }
            case "finish" -> {
                fields(input, Set.of("execution", "status", "lastBatchSeq", "finalUsage", "promptTokens",
                        "completionTokens", "cancellationReason"));
                if (!input.path("finalUsage").isBoolean()) throw ConversationService.invalid();
                var result = execution.finish(key(input), text(input, "status"), number(input, "lastBatchSeq"),
                        input.path("finalUsage").booleanValue(), nullableLong(input, "promptTokens"),
                        nullableLong(input, "completionTokens"), input.path("cancellationReason").isMissingNode()
                                || input.path("cancellationReason").isNull() ? null : text(input, "cancellationReason"));
                yield result == null ? Map.of("settlementRequired", false) : SettlementDeliveryClient.payload(result);
            }
            default -> throw ConversationService.missing();
        };
    }

    private ExecutionKey key(JsonNode input) {
        var key = convert(input.get("execution"), ExecutionKey.class);
        if (key == null || key.ownerUserId() <= 0 || key.conversationId() <= 0 || key.executionEpoch() < 0 || key.authEpoch() < 1
                || key.turnId() == null || key.ticket() == null || key.ticket().length() > 64)
            throw ConversationService.invalid();
        return key;
    }

    private <T> T convert(JsonNode value, Class<T> type) {
        try {
            return json.treeToValue(value, type);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw ConversationService.invalid();
        }
    }

    private static void fields(JsonNode input, Set<String> allowed) {
        input.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) throw ConversationService.invalid();
        });
    }

    private static String text(JsonNode input, String key) {
        if (!input.path(key).isTextual()) throw ConversationService.invalid();
        return input.path(key).textValue();
    }

    private static long number(JsonNode input, String key) {
        var value = input.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) throw ConversationService.invalid();
        return value.longValue();
    }

    private static Long nullableLong(JsonNode input, String key) {
        return input.path(key).isMissingNode() || input.path(key).isNull() ? null : number(input, key);
    }
}

