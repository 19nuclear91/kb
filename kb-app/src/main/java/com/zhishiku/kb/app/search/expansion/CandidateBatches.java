package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.term.TermInput;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** 只保留当前批候选键，结束位置可跨起点批次；不会加载或缓存词典。 */
public final class CandidateBatches implements Iterable<CandidateBatches.Batch> {
    public static final int START_LIMIT = 128;
    public static final int KEY_CODEPOINT_LIMIT = TermInput.EXPRESSION_LIMIT;
    public static final int KEY_LIMIT = START_LIMIT * KEY_CODEPOINT_LIMIT;
    private final NormalizedQuery query;
    private final Runnable checkpoint;

    /**
     * 一批合法字素起点及其去重键，不携带关系列表。
     * @param firstBoundary 首个起点在规范化边界表中的下标
     * @param afterBoundary 最后起点之后的边界下标
     * @param keys 有界、确定顺序的比较键
     */
    public record Batch(int firstBoundary, int afterBoundary, List<String> keys) {
        public Batch {
            if (firstBoundary < 0 || afterBoundary <= firstBoundary || afterBoundary - firstBoundary > START_LIMIT
                    || keys == null || keys.size() > KEY_LIMIT) {
                throw new IllegalArgumentException("词典候选批次无效");
            }
            keys = List.copyOf(keys);
        }

        @Override
        public String toString() {
            return "TermCandidateBatch[redacted]";
        }
    }

    public CandidateBatches(NormalizedQuery query, Runnable checkpoint) {
        this.query = Objects.requireNonNull(query);
        this.checkpoint = Objects.requireNonNull(checkpoint);
    }

    @Override
    public Iterator<Batch> iterator() {
        return new Iterator<>() {
            private int nextBoundary;

            @Override
            public boolean hasNext() {
                checkpoint.run();
                return nextBoundary < query.boundaryCount() - 1;
            }

            @Override
            public Batch next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                int first = nextBoundary;
                int after = Math.min(first + START_LIMIT, query.boundaryCount() - 1);
                var keys = new LinkedHashSet<String>();
                for (int startIndex = first; startIndex < after; startIndex++) {
                    checkpoint.run();
                    int start = query.boundaryAt(startIndex);
                    for (int endIndex = startIndex + 1; endIndex < query.boundaryCount(); endIndex++) {
                        checkpoint.run();
                        int end = query.boundaryAt(endIndex);
                        if (end - start > KEY_CODEPOINT_LIMIT) {
                            break;
                        }
                        if (query.eligible(start, end)) {
                            keys.add(query.comparisonKey(start, end));
                        }
                    }
                }
                checkpoint.run();
                nextBoundary = after;
                return new Batch(first, after, List.copyOf(keys));
            }
        };
    }

    @Override
    public String toString() {
        return "TermCandidateBatches[redacted]";
    }
}
