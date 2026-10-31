package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.model.SearchFailure;

import java.util.function.LongSupplier;

/** 一次搜索的单调总期限与固定阶段窗口。 */
public final class SearchBudget {
    public enum Stage {
        AUTHORIZATION, CONTROL, EMBEDDING, RETRIEVAL, RERANK, RESPONSE
    }

    private final SearchExecutionProperties properties;
    private final LongSupplier clock;
    private final Window total;
    private final Window work;

    public SearchBudget(SearchExecutionProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
        long started = clock.getAsLong();
        this.total = new Window(clock, started, nanos(properties.totalTimeoutMs()));
        this.work = new Window(clock, started, nanos(properties.totalTimeoutMs() - properties.responseReserveMs()));
    }

    public Window total() {
        return total;
    }

    public Window work() {
        return work;
    }

    public long available(Stage stage) {
        long left = work.remainingNanos();
        return switch (stage) {
            case EMBEDDING -> Math.max(0, Math.min(nanos(properties.embeddingTimeoutMs()),
                    left - nanos(properties.retrievalTimeoutMs())));
            case RETRIEVAL -> Math.min(left, nanos(properties.retrievalTimeoutMs()));
            case RERANK -> Math.min(left, nanos(properties.rerankTimeoutMs()));
            case RESPONSE -> total.remainingNanos();
            default -> left;
        };
    }

    public Window begin(Stage stage) {
        long now = clock.getAsLong();
        return new Window(clock, now, available(stage));
    }

    private static long nanos(long millis) {
        return Math.multiplyExact(millis, 1_000_000L);
    }

    /** 已固定起点的窗口；重复读取不会延长期限。 */
    public static final class Window {
        private final LongSupplier clock;
        private final long started;
        private final long allowance;

        private Window(LongSupplier clock, long started, long allowance) {
            this.clock = clock;
            this.started = started;
            this.allowance = allowance;
        }

        public long remainingNanos() {
            long elapsed = clock.getAsLong() - started;
            if (elapsed < 0 || elapsed >= allowance) {
                return 0;
            }
            return allowance - elapsed;
        }

        public void check() {
            if (remainingNanos() <= 0) {
                throw new SearchFailure(SearchFailure.Kind.TIMEOUT);
            }
        }

        public int queryTimeoutSeconds() {
            check();
            long value = remainingNanos();
            long seconds = value / 1_000_000_000L + (value % 1_000_000_000L == 0 ? 0 : 1);
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
        }
    }
}
