package com.zhishiku.kb.app.reporting;

import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.reporting.service.OperationsWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsWindowTest {
    private final Instant now = Instant.parse("2026-10-27T12:00:00.123456Z");

    @Test
    void defaultsAndOffsetsUseOneDatabaseMicrosecondAnchor() {
        assertThat(OperationsWindow.parse(Map.of(), now)).isEqualTo(new OperationsWindow(now.minusSeconds(604800), now));
        var value = OperationsWindow.parse(Map.of("from", List.of("2026-10-26T20:00:00.123456999+08:00"),
                "to", List.of("2026-10-27T20:00:00.123456999+08:00")), now);
        assertThat(value.to()).isEqualTo(now);
        assertThat(value.from()).isEqualTo(now.minusSeconds(86400));
        assertThat(OperationsWindow.parse(Map.of("from", List.of(now.minusSeconds(31L * 86400).toString()),
                "to", List.of(now.toString())), now).to()).isEqualTo(now);
    }

    @Test
    void rejectsUnknownRepeatedEmptyOneSidedFutureAndInvalidBoundaries() {
        for (var raw : List.of(Map.of("userId", List.of("7")), Map.of("from", List.of(now.toString())),
                Map.of("from", List.of(""), "to", List.of(now.toString())),
                Map.of("from", List.of(now.toString(), now.toString()), "to", List.of(now.toString())))) {
            assertThatThrownBy(() -> OperationsWindow.parse(raw, now)).isInstanceOf(QueryException.class);
        }
        for (String start : List.of("2026-10-27T12:00:00.123456Z", "2026-10-27T12:00:00.123455999Z",
                "2026-09-26T12:00:00.123455Z", "2026-10-26T12:00:00", "2026-02-30T00:00:00Z")) {
            String end = start.contains("123455999") ? "2026-10-27T12:00:00.123455Z" : now.toString();
            assertThatThrownBy(() -> OperationsWindow.parse(Map.of("from", List.of(start), "to", List.of(end)), now))
                    .isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> OperationsWindow.parse(Map.of("from", List.of(now.minusSeconds(1).toString()),
                "to", List.of("2026-10-27T12:00:00.123457Z")), now)).isInstanceOf(QueryException.class);
    }
}
