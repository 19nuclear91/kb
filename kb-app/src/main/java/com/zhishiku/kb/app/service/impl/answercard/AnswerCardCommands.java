package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardBinding;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardCommandMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.TreeMap;

/** 操作域、身份、预期版本与规范化载荷绑定；业务服务在读取回执前负责当前鉴权。 */
@Service
public class AnswerCardCommands {
    private final AnswerCardCommandMapper mapper;
    private final ObjectMapper json;

    public AnswerCardCommands(AnswerCardCommandMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public AnswerCardBinding bind(AnswerCardOperation operation, String requestId, long actor, long space,
                                  Long card, Long branch, Long revision, Long review, Long expected, Object input) {
        if (operation == null || actor <= 0 || space <= 0 || requestId == null) {
            throw AnswerCardFailure.invalid();
        }
        AnswerCardContent.text(requestId, 0, 128);
        int requestLength = requestId.codePointCount(0, requestId.length());
        if (requestLength < 1 || requestLength > 128 || requestId.codePoints().anyMatch(Character::isISOControl)) {
            throw AnswerCardFailure.invalid();
        }
        for (Long id : Arrays.asList(card, branch, revision, review)) {
            if (id != null && id <= 0) {
                throw AnswerCardFailure.invalid();
            }
        }
        if (expected != null && expected < 0) {
            throw AnswerCardFailure.invalid();
        }
        JsonNode content = json.valueToTree(Arrays.asList(1, operation.name(), actor, space, card, branch,
                revision, review, expected, input));
        return new AnswerCardBinding(operation, requestId, actor, space, card, branch, revision, review, expected,
                digest(write(canonical(content))));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AnswerCardCommand lock(AnswerCardBinding binding) {
        mapper.lockRequest("answer-card:" + binding.operation().name() + ":" + binding.requestId());
        return find(binding);
    }

    public AnswerCardCommand find(AnswerCardBinding binding) {
        AnswerCardCommand previous = mapper.find(binding.operation().name(), binding.requestId());
        binding.requireMatches(previous);
        return previous;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(AnswerCardBinding binding, Object receipt) {
        String encoded = write(receipt);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 16384) {
            throw AnswerCardFailure.unavailable();
        }
        AnswerCardCommand row = new AnswerCardCommand(binding.operation(), binding.requestId(), binding.actorId(),
                binding.spaceId(), binding.cardId(), binding.branchId(), binding.revisionId(), binding.reviewId(),
                binding.expectedCardVersion(), 1, binding.digest(), encoded, null);
        if (mapper.insert(row) != 1) {
            throw AnswerCardFailure.unavailable();
        }
    }

    public <T> T receipt(AnswerCardCommand command, Class<T> type) {
        try {
            return json.readValue(command.receipt(), type);
        } catch (JsonProcessingException ignored) {
            throw AnswerCardFailure.unavailable();
        }
    }

    public String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ignored) {
            throw AnswerCardFailure.unavailable();
        }
    }

    public boolean sameJson(String stored, Object value) {
        try {
            return json.readTree(stored).equals(json.valueToTree(value));
        } catch (JsonProcessingException ignored) {
            throw AnswerCardFailure.unavailable();
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            TreeMap<String, JsonNode> fields = new TreeMap<>();
            value.fields().forEachRemaining(entry -> fields.put(entry.getKey(), canonical(entry.getValue())));
            ObjectNode result = json.createObjectNode();
            fields.forEach(result::set);
            return result;
        }
        if (value.isArray()) {
            var result = json.createArrayNode();
            value.forEach(element -> result.add(canonical(element)));
            return result;
        }
        return value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用");
        }
    }
}
