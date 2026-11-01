package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.search.model.SearchFilters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 按实际 JSON 类型读取筛选条件，拒绝歧义与静默类型转换。 */
public final class SearchRequestDeserializer extends JsonDeserializer<SearchRequest> {
    private static final Set<String> FIELDS = Set.of("query", "candidates", "topK", "spaceId", "categoryId",
            "uncategorized", "tagIds", "fileType", "updatedFrom", "updatedTo");

    @Override
    public SearchRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        var root = SearchJson.object(parser);
        SearchJson.fields(root, FIELDS);
        return new SearchRequest(SearchJson.text(root.get("query")), amount(root.get("candidates")),
                amount(root.get("topK")), SearchJson.nullableId(root.get("spaceId")),
                SearchJson.nullableId(root.get("categoryId")), SearchJson.bool(root.get("uncategorized")),
                tags(root.get("tagIds")), fileType(root.get("fileType")),
                SearchJson.time(root.get("updatedFrom")), SearchJson.time(root.get("updatedTo")));
    }

    private static Integer amount(JsonNode value) {
        return value == null || value.isNull() ? null : (int) SearchJson.number(value, 1, 200);
    }

    private static List<Long> tags(JsonNode value) {
        if (value == null) {
            return List.of();
        }
        if (!value.isArray() || value.size() > 20) {
            throw SearchJson.invalid();
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode id : value) {
            ids.add(SearchJson.number(id, 1, Long.MAX_VALUE));
        }
        return ids;
    }

    private static SearchFilters.FileType fileType(JsonNode value) {
        if (value == null) {
            return null;
        }
        try {
            return SearchFilters.FileType.valueOf(SearchJson.text(value));
        } catch (IllegalArgumentException invalidType) {
            throw SearchJson.invalid();
        }
    }
}
