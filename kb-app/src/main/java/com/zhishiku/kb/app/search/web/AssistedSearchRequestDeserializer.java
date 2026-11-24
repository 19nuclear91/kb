package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.model.SearchFailure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AssistedSearchRequestDeserializer extends JsonDeserializer<AssistedSearchRequest> {
    private static final Set<String> FIELDS;

    static {
        var fields = new HashSet<>(SearchRequestDeserializer.FIELDS);
        fields.addAll(Set.of("mode", "selectionContext", "selections"));
        FIELDS = Set.copyOf(fields);
    }

    @Override
    public AssistedSearchRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        ObjectNode root = SearchJson.object(parser);
        SearchJson.fields(root, FIELDS);
        try {
            var mode = root.has("mode") ? SearchQueryPlan.Mode.valueOf(SearchJson.text(root.get("mode"))) : SearchQueryPlan.Mode.ASSISTED;
            String credential = root.hasNonNull("selectionContext") ? SearchJson.text(root.get("selectionContext")) : null;
            List<SearchQueryPlan.Selection> selections = new ArrayList<>();
            if (root.has("selections")) {
                var values = root.get("selections");
                if (!values.isArray()) {
                    throw SearchJson.invalid();
                }
                if (values.size() > 8) {
                    throw new SearchFailure(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
                }
                for (var value : values) {
                    if (!(value instanceof ObjectNode item)) {
                        throw SearchJson.invalid();
                    }
                    SearchJson.fields(item, Set.of("kind", "start", "end", "termId"));
                    selections.add(new SearchQueryPlan.Selection(SearchQueryPlan.SelectionKind.valueOf(SearchJson.text(item.get("kind"))),
                            (int) SearchJson.number(item.get("start"), 0, Integer.MAX_VALUE),
                            (int) SearchJson.number(item.get("end"), 1, Integer.MAX_VALUE), SearchJson.number(item.get("termId"), 1, Long.MAX_VALUE)));
                }
            }
            return new AssistedSearchRequest(SearchRequestDeserializer.base(root), mode, credential, selections);
        } catch (IllegalArgumentException invalid) {
            throw SearchJson.invalid();
        }
    }
}
