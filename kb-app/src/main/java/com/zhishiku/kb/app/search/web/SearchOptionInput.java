package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchOptions;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/** 目录正文独立限制为 16 KiB，全部条件通过请求体传输。 */
public final class SearchOptionInput {
    public static final int MAX_BYTES = 16384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(8).maxStringLength(MAX_BYTES).maxNumberLength(20).build()).build());

    private SearchOptionInput() {
    }

    public static ObjectNode body(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw SearchJson.invalid();
        }
        try {
            return body(request.getInputStream());
        } catch (IOException failure) {
            throw SearchJson.invalid();
        }
    }

    public static ObjectNode body(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new SearchFailure(SearchFailure.Kind.REQUEST_TOO_LARGE);
            }
            try (var parser = JSON.createParser(bytes)) {
                return SearchJson.object(parser);
            }
        } catch (IOException failure) {
            throw SearchJson.invalid();
        }
    }

    public static SearchOptions.CategoryQuery category(ObjectNode root) {
        SearchJson.fields(root, Set.of("spaceId", "parentId", "limit", "cursor"));
        return new SearchOptions.CategoryQuery(SearchJson.number(root.get("spaceId"), 1, Long.MAX_VALUE),
                SearchJson.nullableId(root.get("parentId")), limit(root), cursor(root));
    }

    public static SearchOptions.TagQuery tag(ObjectNode root) {
        SearchJson.fields(root, Set.of("nameQuery", "limit", "cursor"));
        return new SearchOptions.TagQuery(root.has("nameQuery") ? SearchJson.text(root.get("nameQuery")) : null,
                limit(root), cursor(root));
    }

    private static int limit(ObjectNode root) {
        return root.has("limit") ? (int) SearchJson.number(root.get("limit"), 1, 100) : 20;
    }

    private static String cursor(ObjectNode root) {
        return root.has("cursor") ? SearchJson.text(root.get("cursor")) : null;
    }
}
