package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.model.DocumentFavoriteRow;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFavoriteViewsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesExactIdentitiesAndNullableVersionsWithoutInternalPosition() throws Exception {
        var row = new DocumentFavoriteRow(123, Long.MAX_VALUE, "标题", 9007199254740993L,
                (short) 2, null, null, Instant.parse("2026-10-24T00:00:00Z"));
        var value = json.readTree(json.writeValueAsBytes(row.view()));
        assertThat(value.size()).isEqualTo(7);
        assertThat(value.get("documentId").isIntegralNumber()).isTrue();
        assertThat(value.get("documentId").longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(value.get("spaceId").longValue()).isEqualTo(9007199254740993L);
        assertThat(value.get("currentVersionId").isNull()).isTrue();
        assertThat(value.get("currentVersionNo").isNull()).isTrue();
        assertThat(value.has("favoriteId")).isFalse();
    }

    @Test
    void stateAndPageHaveOnlyTheirDeclaredFields() throws Exception {
        var state = new DocumentFavoriteViews.State(Long.MAX_VALUE, false);
        var value = json.readTree(json.writeValueAsBytes(new DocumentFavoriteViews.States(List.of(state))));
        assertThat(value.size()).isEqualTo(1);
        assertThat(value.get("items").get(0).size()).isEqualTo(2);
        assertThat(value.get("items").get(0).get("favorited").booleanValue()).isFalse();
        var page = json.readTree(json.writeValueAsBytes(new DocumentFavoriteViews.Page(List.of(), false, null)));
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.get("items").isEmpty()).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
        assertThat(page.has("total")).isFalse();
    }
}
