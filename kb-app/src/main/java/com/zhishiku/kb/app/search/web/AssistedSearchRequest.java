package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchInput;

import java.util.Comparator;
import java.util.List;

/** 增强入口的平坦 JSON 契约；不扩大旧入口的字段集合。 */
@JsonDeserialize(using = AssistedSearchRequestDeserializer.class)
public record AssistedSearchRequest(SearchRequest base, SearchQueryPlan.Mode mode, String selectionContext,
                                     List<SearchQueryPlan.Selection> selections) {
    public AssistedSearchRequest {
        if (base == null || mode == null || selections == null) {
            throw SearchJson.invalid();
        }
        SearchInput.validate(base.query(), 1, 1, Integer.MAX_VALUE);
        if (selections.size() > 8) {
            throw new SearchFailure(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
        }
        int length = base.query().codePointCount(0, base.query().length());
        for (SearchQueryPlan.Selection selection : selections) {
            if (selection == null || selection.kind() == null || selection.termId() <= 0 || selection.start() < 0
                    || selection.end() <= selection.start() || selection.end() > length) {
                throw SearchJson.invalid();
            }
        }
        selections = selections.stream().sorted(Comparator.comparingInt(SearchQueryPlan.Selection::start)).toList();
        int end = 0;
        for (SearchQueryPlan.Selection selection : selections) {
            if (selection.start() < end) {
                throw SearchJson.invalid();
            }
            end = selection.end();
        }
        if (selections.isEmpty() ? selectionContext != null
                : selectionContext == null || selectionContext.isBlank() || selectionContext.length() > 4096) {
            throw SearchJson.invalid();
        }
        if (mode == SearchQueryPlan.Mode.ORIGINAL && (!selections.isEmpty() || selectionContext != null)) {
            throw SearchJson.invalid();
        }
    }

    public int candidates() {
        return base.candidates() == null ? 50 : base.candidates();
    }

    public int topK() {
        return base.topK() == null ? candidates() : base.topK();
    }

    public void validate(int maxCodepoints) {
        SearchInput.validate(base.query(), candidates(), topK(), maxCodepoints);
    }

    @Override
    public String toString() {
        return "AssistedSearchRequest[redacted]";
    }
}
