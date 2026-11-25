package com.zhishiku.kb.app.search.model;

import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;

import java.util.Comparator;
import java.util.List;

/** 辅助检索的不可变服务输入，与 HTTP 反序列化及请求类型隔离。 */
public record AssistedSearchInput(
        String query,
        int candidates,
        int topK,
        SearchFilters filters,
        SearchQueryPlan.Mode mode,
        String selectionContext,
        List<SearchQueryPlan.Selection> selections
) {
    public AssistedSearchInput {
        if (filters == null || mode == null || selections == null) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        SearchInput.validate(query, candidates, topK, Integer.MAX_VALUE);
        if (selections.size() > 8) {
            throw new SearchFailure(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
        }
        int length = query.codePointCount(0, query.length());
        for (SearchQueryPlan.Selection selection : selections) {
            if (selection == null || selection.kind() == null || selection.termId() <= 0 || selection.start() < 0
                    || selection.end() <= selection.start() || selection.end() > length) {
                throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
            }
        }
        selections = selections.stream().sorted(Comparator.comparingInt(SearchQueryPlan.Selection::start)).toList();
        int end = 0;
        for (SearchQueryPlan.Selection selection : selections) {
            if (selection.start() < end) {
                throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
            }
            end = selection.end();
        }
        if (selections.isEmpty() ? selectionContext != null
                : selectionContext == null || selectionContext.isBlank() || selectionContext.length() > 4096) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        if (mode == SearchQueryPlan.Mode.ORIGINAL && (!selections.isEmpty() || selectionContext != null)) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
    }

    public void validate(int maxCodepoints) {
        SearchInput.validate(query, candidates, topK, maxCodepoints);
    }

    @Override
    public String toString() {
        return "AssistedSearchInput[redacted]";
    }
}
