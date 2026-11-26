package com.zhishiku.kb.app.controller.answercard;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardIntent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardPageInput;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** 新协议专用严格解码器，不更改已有文档接口的容错规则。 */
public final class AnswerCardInput {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(10).maxStringLength(65536).maxNumberLength(20).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private AnswerCardInput() {
    }

    public static JsonNode body(HttpServletRequest request, int maximum) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw AnswerCardFailure.invalid();
        }
        try {
            return body(request.getInputStream(), maximum);
        } catch (IOException ignored) {
            throw AnswerCardFailure.invalid();
        }
    }

    public static JsonNode body(InputStream input, int maximum) {
        try {
            byte[] bytes = input.readNBytes(maximum + 1);
            if (bytes.length > maximum) {
                throw new AnswerCardFailure(413, "BODY_TOO_LARGE");
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw AnswerCardFailure.invalid();
                }
                strings(node);
                return node;
            }
        } catch (IOException ignored) {
            throw AnswerCardFailure.invalid();
        }
    }

    private static void strings(JsonNode node) {
        if (node.isTextual()) {
            AnswerCardContent.text(node.textValue(), 0, 65536);
        } else if (node.isContainerNode()) {
            var names = node.fieldNames();
            while (names.hasNext()) {
                AnswerCardContent.text(names.next(), 0, 128);
            }
            node.forEach(AnswerCardInput::strings);
        }
    }

    public static void fields(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) {
            throw AnswerCardFailure.invalid();
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            if (!allowed.contains(names.next())) {
                throw AnswerCardFailure.invalid();
            }
        }
        for (String name : required) {
            if (!node.has(name)) {
                throw AnswerCardFailure.invalid();
            }
        }
    }

    public static AnswerCardIntent intent(AnswerCardOperation operation, Long card, Long review, JsonNode node) {
        Set<String> required = switch (operation) {
            case ANSWER_CARD_CREATE -> Set.of("spaceId", "content");
            case ANSWER_CARD_SAVE -> Set.of("spaceId", "expectedCardVersion", "branchId", "revisionId", "content");
            case ANSWER_CARD_SUBMIT -> Set.of("spaceId", "expectedCardVersion", "branchId", "revisionId");
            case ANSWER_CARD_APPROVE, ANSWER_CARD_WITHDRAW -> Set.of("spaceId", "expectedCardVersion", "branchId", "revisionId", "expectedReviewVersion");
            case ANSWER_CARD_REJECT -> Set.of("spaceId", "expectedCardVersion", "branchId", "revisionId", "expectedReviewVersion", "reason");
            case ANSWER_CARD_STEWARDSHIP_UPDATE -> Set.of("spaceId", "expectedCardVersion", "expectedStewardshipVersion", "responsibleUserId", "cycleMonths");
            case ANSWER_CARD_CONFIRM -> Set.of("spaceId", "expectedCardVersion", "revisionId", "expectedStewardshipVersion");
        };
        fields(node, required, required);
        boolean decision = Set.of(AnswerCardOperation.ANSWER_CARD_APPROVE, AnswerCardOperation.ANSWER_CARD_REJECT,
                AnswerCardOperation.ANSWER_CARD_WITHDRAW).contains(operation);
        if ((operation == AnswerCardOperation.ANSWER_CARD_CREATE) != (card == null) || decision != (review != null)) {
            throw AnswerCardFailure.invalid();
        }
        return new AnswerCardIntent(operation, number(node.get("spaceId"), false), card,
                node.has("branchId") ? nullableId(node.get("branchId"), operation == AnswerCardOperation.ANSWER_CARD_SAVE) : null,
                node.has("revisionId") ? number(node.get("revisionId"), false) : null, review,
                node.has("expectedCardVersion") ? number(node.get("expectedCardVersion"), true) : null,
                node.has("expectedReviewVersion") ? number(node.get("expectedReviewVersion"), true) : null,
                node.has("expectedStewardshipVersion") ? number(node.get("expectedStewardshipVersion"), true) : null,
                node.has("responsibleUserId") ? number(node.get("responsibleUserId"), false) : null,
                node.has("cycleMonths") ? integer(node.get("cycleMonths"), 3, 24) : null,
                node.has("reason") ? AnswerCardContent.text(text(node.get("reason")), 1, 2000) : null,
                node.has("content") ? content(node.get("content")) : null);
    }

    private static AnswerCardContent content(JsonNode node) {
        var required = Set.of("question", "aliases", "answer", "applicabilityText", "dates", "sources");
        fields(node, required, required);
        if (!node.get("aliases").isArray() || !node.get("sources").isArray()
                || node.get("aliases").size() > 20 || node.get("sources").size() > 10) {
            throw AnswerCardFailure.invalid();
        }
        var aliases = new ArrayList<String>();
        node.get("aliases").forEach(value -> aliases.add(text(value)));
        var sources = new ArrayList<AnswerCardSourceKey>();
        node.get("sources").forEach(value -> {
            var fields = Set.of("documentId", "versionId", "applicabilityVersion");
            fields(value, fields, fields);
            sources.add(new AnswerCardSourceKey(number(value.get("documentId"), false), number(value.get("versionId"), false),
                    number(value.get("applicabilityVersion"), true)));
        });
        var dates = node.get("dates");
        var fields = Set.of("effectiveFrom", "untilKind", "effectiveUntil");
        fields(dates, fields, fields);
        return new AnswerCardContent(text(node.get("question")), aliases, text(node.get("answer")), text(node.get("applicabilityText")),
                new AnswerCardDates(date(dates.get("effectiveFrom")), text(dates.get("untilKind")), date(dates.get("effectiveUntil"))), sources);
    }

    private static LocalDate date(JsonNode node) {
        if (node != null && node.isNull()) {
            return null;
        }
        String value = text(node);
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            throw AnswerCardFailure.invalid();
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            throw AnswerCardFailure.invalid();
        }
    }

    public static Long nullableId(JsonNode node, boolean nullable) {
        if (nullable && (node == null || node.isNull())) {
            return null;
        }
        return number(node, false);
    }

    public static long number(JsonNode node, boolean zero) {
        return number(text(node), zero);
    }

    public static long number(String value, boolean zero) {
        if (value == null || !value.matches(zero ? "0|[1-9][0-9]{0,18}" : "[1-9][0-9]{0,18}")) {
            throw AnswerCardFailure.invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            throw AnswerCardFailure.invalid();
        }
    }

    public static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw AnswerCardFailure.invalid();
        }
        return node.textValue();
    }

    public static int integer(JsonNode node, int minimum, int maximum) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()
                || node.intValue() < minimum || node.intValue() > maximum) {
            throw AnswerCardFailure.invalid();
        }
        return node.intValue();
    }

    public static AnswerCardPageInput candidate(JsonNode node, boolean source) {
        fields(node, source ? Set.of("spaceId", "name", "limit", "cursor") : Set.of("name", "limit", "cursor"),
                source ? Set.of("spaceId", "name") : Set.of("name"));
        return new AnswerCardPageInput(source ? number(node.get("spaceId"), false) : null, null, null,
                AnswerCardContent.text(text(node.get("name")), 0, source ? 128 : 64),
                node.has("limit") ? integer(node.get("limit"), 1, 100) : 20,
                node.hasNonNull("cursor") ? text(node.get("cursor")) : null);
    }

    public static AnswerCardPageInput page(HttpServletRequest request, String defaultView, String... allowed) {
        var input = new LinkedHashMap<String, List<String>>();
        request.getParameterMap().forEach((key, value) -> input.put(key, Arrays.asList(value)));
        var parameters = new QueryParameters(input, allowed);
        try {
            if (request.getInputStream().read() != -1) {
                throw AnswerCardFailure.invalid();
            }
        } catch (IOException ignored) {
            throw AnswerCardFailure.invalid();
        }
        String view = parameters.exactText("view", 20);
        return new AnswerCardPageInput(parameters.positive("spaceId"), view == null ? defaultView : view,
                parameters.positive("revisionId"), null, parameters.limit(), parameters.cursor());
    }
}
