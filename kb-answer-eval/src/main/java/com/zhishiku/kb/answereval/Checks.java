package com.zhishiku.kb.answereval;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** v1 契约的纯值校验；失败诊断不引用输入值。 */
public final class Checks {
    private Checks() {
    }

    public static void require(boolean valid) {
        if (!valid) {
            throw new EvaluationFailure(EvaluationFailure.Code.INPUT);
        }
    }

    public static String id(String value) {
        require(value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"));
        return value;
    }

    public static String sha(String value) {
        require(value != null && value.matches("[a-f0-9]{64}"));
        return value;
    }

    public static String text(String value, int maxCodePoints) {
        require(value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maxCodePoints);
        unicode(value);
        return value;
    }

    public static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            require(c != 0);
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(++i)));
            } else {
                require(!Character.isLowSurrogate(c));
            }
        }
    }

    public static String uuid(String value) {
        require(value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"));
        require(UUID.fromString(value).toString().equals(value));
        return value;
    }

    public static void unique(Collection<String> values) {
        require(values != null && values.stream().noneMatch(Objects::isNull) && new HashSet<>(values).size() == values.size());
    }
}
