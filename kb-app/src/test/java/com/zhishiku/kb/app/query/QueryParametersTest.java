package com.zhishiku.kb.app.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParametersTest {
    @Test
    void rejectsUnknownRepeatedBlankAndMalformedTypedValues() {
        for (Map<String, List<String>> values : List.of(Map.of("title", List.of("secret")),
                Map.of("limit", List.of("1", "2")), Map.of("limit", List.of(" ")))) {
            assertThatThrownBy(() -> new QueryParameters(values, "limit")).isInstanceOf(QueryException.class);
        }
        for (String value : List.of("0", "-1", "101", "1.5", "9223372036854775808", "01", " 2")) {
            assertThatThrownBy(() -> new QueryParameters(Map.of("limit", List.of(value)), "limit").limit())
                    .isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> new QueryParameters(Map.of("enabled", List.of("TRUE")), "enabled").bool("enabled"))
                .isInstanceOf(QueryException.class);
        assertThat(new QueryParameters(Map.of()).limit()).isEqualTo(20);
    }

    @Test
    void unicodeAndLikeProcessingAreLiteralAndTimesUseMicros() {
        var parameters = new QueryParameters(Map.of("q", List.of(" 😀%_\\ ")), "q");
        assertThat(parameters.text("q", 4)).isEqualTo("😀%_\\");
        assertThat(QueryParameters.like(parameters.text("q", 4))).isEqualTo("%😀\\%\\_\\\\%");
        assertThatThrownBy(() -> parameters.text("q", 3)).isInstanceOf(QueryException.class);
        assertThat(QueryParameters.parseInstant("2026-10-17T08:00:00.123456789+08:00"))
                .isEqualTo(Instant.parse("2026-10-17T00:00:00.123456Z"));
        assertThatThrownBy(() -> QueryParameters.parseInstant("2026-10-17T08:00:00"))
                .isInstanceOf(QueryException.class);
    }
}
