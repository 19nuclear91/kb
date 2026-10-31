package com.zhishiku.kb.app.search.model;

/** HTTP 与内部检索入口共用的输入校验。 */
public final class SearchInput {
    private SearchInput() {
    }

    public static void validate(String query, int candidates, int topK, int maxCodepoints) {
        if (query == null || query.isBlank() || candidates < 1 || candidates > 200 || topK < 1 || topK > 200) {
            throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
        }
        int count = 0;
        for (int index = 0; index < query.length(); index++) {
            char value = query.charAt(index);
            if (value == 0 || Character.isLowSurrogate(value)) {
                throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
            }
            if (Character.isHighSurrogate(value)) {
                if (++index >= query.length() || !Character.isLowSurrogate(query.charAt(index))) {
                    throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
                }
            }
            if (++count > maxCodepoints) {
                throw new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
            }
        }
    }
}
