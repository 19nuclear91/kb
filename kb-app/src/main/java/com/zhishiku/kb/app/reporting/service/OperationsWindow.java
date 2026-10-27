package com.zhishiku.kb.app.reporting.service;

import com.zhishiku.kb.app.query.QueryException;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record OperationsWindow(Instant from, Instant to) {
    public static OperationsWindow parse(Map<String, List<String>> input, Instant asOf) {
        for (var entry : input.entrySet()) {
            var values = entry.getValue();
            if (!Set.of("from", "to").contains(entry.getKey()) || values == null || values.size() != 1
                    || values.getFirst() == null || values.getFirst().isBlank() || values.getFirst().length() > 64) {
                throw QueryException.invalid();
            }
        }
        if (input.containsKey("from") != input.containsKey("to")) {
            throw QueryException.invalid();
        }
        Instant end = input.isEmpty() ? asOf : time(input.get("to").getFirst());
        Instant start = input.isEmpty() ? end.minus(7, ChronoUnit.DAYS) : time(input.get("from").getFirst());
        if (!start.isBefore(end) || end.isAfter(asOf) || Duration.between(start, end).compareTo(Duration.ofDays(31)) > 0) {
            throw QueryException.invalid();
        }
        return new OperationsWindow(start, end);
    }

    private static Instant time(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (RuntimeException ignored) {
            throw QueryException.invalid();
        }
    }
}
