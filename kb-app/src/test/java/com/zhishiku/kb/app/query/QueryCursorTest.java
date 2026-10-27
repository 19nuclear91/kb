package com.zhishiku.kb.app.query;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryCursorTest {
    @Test
    void bindsFiltersAndKindWithoutSerializingOriginalSearchValues() {
        var filters = QueryParameters.filters("target", 7, "q", "sensitive-name");
        String token = QueryCursor.encode("users", filters, 12);
        assertThat(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)).doesNotContain(
                "sensitive-name");
        var cursor = QueryCursor.decode(token, "users");
        assertThat(cursor.id()).isEqualTo(12);
        cursor.verify("users", filters);
        assertThatThrownBy(() -> cursor.verify("users", QueryParameters.filters("target", 8)))
                .isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> QueryCursor.decode(token, "roles")).isInstanceOf(QueryException.class);
    }

    @Test
    void rejectsOversizedDuplicateTrailingAndCoercedCursorFields() {
        String valid = new String(Base64.getUrlDecoder().decode(QueryCursor.encode("users", Map.of(), 3)),
                StandardCharsets.UTF_8);
        for (String json : List.of(valid + "{}", valid.replace("\"id\":3", "\"id\":3,\"id\":4"),
                valid.replace("\"id\":3", "\"id\":3.0"), valid.replace("\"id\":3", "\"id\":0"),
                valid.replace("\"id\":3", "\"id\":9223372036854775808"),
                valid.replace("\"id\":3", "\"id\":3,\"unknown\":true"))) {
            String token =
                    Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> QueryCursor.decode(token, "users")).isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> QueryCursor.decode("A".repeat(2732), "users")).isInstanceOf(QueryException.class);
    }

    @Test
    void preservesAuditWindowAndPageUsesOnlyLastVisibleRow() {
        Instant from = Instant.parse("2026-10-10T00:00:00Z"), to = Instant.parse("2026-10-17T00:00:00Z");
        String token = QueryCursor.encode("audit", Map.of(), 8, from.plusSeconds(1), from, to);
        assertThat(QueryCursor.decode(token, "audit").from()).isEqualTo(from);
        assertThat(QueryCursor.decode(token, "audit").to()).isEqualTo(to);
        var page = QueryPage.slice(List.of(1L, 2L, 3L), 2, id -> QueryCursor.encode("users", Map.of(), id));
        assertThat(page.items()).containsExactly(1L, 2L);
        assertThat(QueryCursor.decode(page.nextCursor(), "users").id()).isEqualTo(2);
        assertThat(QueryPage.slice(List.of(), 20, Object::toString).nextCursor()).isNull();
    }
}
