package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.term.TermSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 一次快照下的最左最长选择；不生成替换、不递归、不截断含义以推断唯一。 */
public final class TermMatcher {
    public static final int MATCH_LIMIT = 8;
    public static final int MEANING_LIMIT = 8;

    /**
     * 原文单个不重叠片段及当前有界启用含义。
     * @param start 原文 code point 起点
     * @param end 原文 code point 终点
     * @param key 完整比较键
     * @param sourceText 原文完整片段
     * @param correction 是否为单向人工错词
     * @param meanings 有序的当前启用含义
     */
    public record Match(int start, int end, String key, String sourceText, boolean correction,
                        List<TermSnapshot.Meaning> meanings) {
        public Match {
            meanings = List.copyOf(meanings);
        }

        @Override
        public String toString() {
            return "TermMatch[redacted]";
        }
    }

    public List<Match> match(NormalizedQuery query, TermSnapshot snapshot, Runnable checkpoint) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(checkpoint);
        var matches = new ArrayList<Match>();
        int coveredUntil = 0;
        for (var batch : new CandidateBatches(query, checkpoint)) {
            checkpoint.run();
            if (query.boundaryAt(batch.afterBoundary() - 1) < coveredUntil) {
                continue;
            }
            Set<String> found = snapshot.probe(batch.keys());
            if (found == null || found.stream().anyMatch(Objects::isNull) || !Set.copyOf(batch.keys()).containsAll(found)) {
                throw unavailable();
            }
            for (int startIndex = batch.firstBoundary(); startIndex < batch.afterBoundary(); startIndex++) {
                checkpoint.run();
                int start = query.boundaryAt(startIndex);
                if (start < coveredUntil) {
                    continue;
                }
                int end = longest(query, startIndex, found, checkpoint);
                if (end < 0) {
                    continue;
                }
                if (matches.size() == MATCH_LIMIT) {
                    throw limit();
                }
                String key = query.comparisonKey(start, end);
                TermSnapshot.Relations relations = snapshot.relations(key);
                if (relations == null || (!relations.terms().isEmpty() && relations.correction() != null)) {
                    throw unavailable();
                }
                List<TermSnapshot.Meaning> meanings = relations.correction() == null ? relations.terms() : List.of(relations.correction());
                if (meanings.isEmpty() || meanings.size() > MEANING_LIMIT + 1) {
                    throw unavailable();
                }
                if (meanings.size() == MEANING_LIMIT + 1) {
                    throw limit();
                }
                long last = 0;
                for (TermSnapshot.Meaning meaning : meanings) {
                    checkpoint.run();
                    if (meaning == null || meaning.termId() <= last) {
                        throw unavailable();
                    }
                    last = meaning.termId();
                }
                NormalizedQuery.Range range = query.originalRange(start, end);
                matches.add(new Match(range.start(), range.end(), key, query.matchedText(start, end), relations.correction() != null, meanings));
                coveredUntil = end;
            }
        }
        checkpoint.run();
        return List.copyOf(matches);
    }

    private static int longest(NormalizedQuery query, int startIndex, Set<String> found, Runnable checkpoint) {
        int start = query.boundaryAt(startIndex);
        int longest = -1;
        for (int index = startIndex + 1; index < query.boundaryCount(); index++) {
            checkpoint.run();
            int end = query.boundaryAt(index);
            if (end - start > CandidateBatches.KEY_CODEPOINT_LIMIT) {
                break;
            }
            if (query.eligible(start, end) && found.contains(query.comparisonKey(start, end))) {
                longest = end;
            }
        }
        return longest;
    }

    private static SearchFailure unavailable() {
        return new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }

    private static SearchFailure limit() {
        return new SearchFailure(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
    }
}
