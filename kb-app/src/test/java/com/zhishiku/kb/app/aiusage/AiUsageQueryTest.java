package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.app.aiusage.service.AiUsageQuery;
import com.zhishiku.kb.app.query.QueryException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiUsageQueryTest {
    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void auditCursorKeepsWindowFilterAndWatermarkAcrossTime() {
        var first = new AiUsageQuery("audits", Map.of("mode", List.of("MULTI")), now);
        first.upper = 9;
        String cursor = first.cursor(8, now.minusSeconds(20), null);
        var next = new AiUsageQuery("audits", Map.of("mode", List.of("MULTI"), "cursor", List.of(cursor)), now.plusSeconds(3600));
        assertThat(next.to).isEqualTo(now);
        assertThat(next.upper).isEqualTo(9);
        assertThat(next.sql).containsEntry("lastId", 8L);
        assertThatThrownBy(() -> new AiUsageQuery("audits", Map.of("mode", List.of("SINGLE"), "cursor", List.of(cursor)), now))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> new AiUsageQuery("groups", Map.of("cursor", List.of(cursor)), now)).isInstanceOf(QueryException.class);
    }

    @Test
    void invalidUnboundedAndDuplicatedConditionsAreRejected() {
        for (Map<String, List<String>> input : List.of(Map.of("mode", List.of("bad")),
                Map.of("userId", List.of("1", "2")), Map.of("unknown", List.of("value")),
                Map.of("userId", List.of("9223372036854775808")), Map.of("cursor", List.of("bad")),
                Map.of("from", List.of("2026-01-01T00:00:00Z"), "to", List.of("2026-03-01T00:00:00Z")))) {
            assertThatThrownBy(() -> new AiUsageQuery("audits", input, now)).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void groupCursorUsesStableKeyAndIndependentKind() {
        var query = new AiUsageQuery("groups", Map.of("groupBy", List.of("user")), now);
        String cursor = query.cursor(0, null, "0009007199254740993");
        var next = new AiUsageQuery("groups", Map.of("groupBy", List.of("user"), "cursor", List.of(cursor)), now);
        assertThat(next.sql).containsEntry("afterGroup", "0009007199254740993");
    }
}
