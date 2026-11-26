package com.zhishiku.kb.app.domain.dto.answercard;

import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** 规范化后不可变的草稿输入，仅在当前业务调用中使用。 */
public record AnswerCardContent(
        String question,
        List<String> aliases,
        String answer,
        String applicabilityText,
        AnswerCardDates dates,
        List<AnswerCardSourceKey> sources
) {
    public AnswerCardContent {
        question = text(question, 1, 256);
        answer = text(answer, 0, 16000);
        applicabilityText = text(applicabilityText, 0, 2000);
        if (aliases == null || aliases.size() > 20 || dates == null || sources == null || sources.size() > 10) {
            throw AnswerCardFailure.invalid();
        }
        var normalized = new HashSet<String>();
        normalized.add(normalize(question));
        var safeAliases = new ArrayList<String>();
        for (String alias : aliases) {
            String safe = text(alias, 1, 256);
            if (!normalized.add(normalize(safe))) {
                throw AnswerCardFailure.invalid();
            }
            safeAliases.add(safe);
        }
        aliases = List.copyOf(safeAliases);
        var documents = new HashSet<Long>();
        for (var source : sources) {
            if (source == null || source.documentId() <= 0 || source.versionId() <= 0
                    || source.applicabilityVersion() < 0 || !documents.add(source.documentId())) {
                throw AnswerCardFailure.invalid();
            }
        }
        sources = sources.stream().sorted(java.util.Comparator.comparingLong(AnswerCardSourceKey::documentId)).toList();
    }

    public void requireComplete() {
        if (answer.isBlank() || applicabilityText.isBlank() || sources.isEmpty()) {
            throw AnswerCardFailure.conflict("INCOMPLETE_DRAFT");
        }
    }

    public List<String> normalizedQuestions() {
        var values = new ArrayList<String>();
        values.add(normalize(question));
        aliases.forEach(alias -> values.add(normalize(alias)));
        return List.copyOf(values);
    }

    public String questionText() {
        return question + "\n" + String.join("\n", aliases);
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static String text(String value, int minimum, int maximum) {
        if (value == null) {
            throw AnswerCardFailure.invalid();
        }
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == 0 || Character.isLowSurrogate(current)) {
                throw AnswerCardFailure.invalid();
            }
            if (Character.isHighSurrogate(current)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw AnswerCardFailure.invalid();
                }
            }
        }
        String safe = value.strip();
        int length = safe.codePointCount(0, safe.length());
        if (length < minimum || length > maximum) {
            throw AnswerCardFailure.invalid();
        }
        return safe;
    }

    @Override
    public String toString() {
        return "AnswerCardContent[redacted]";
    }
}
