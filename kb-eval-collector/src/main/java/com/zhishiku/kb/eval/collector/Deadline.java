package com.zhishiku.kb.eval.collector;

import java.util.function.LongSupplier;

final class Deadline {
    private final LongSupplier clock;
    private final long start;
    private final long budget;

    Deadline(LongSupplier clock, int timeoutMs) {
        this.clock = clock;
        this.start = clock.getAsLong();
        this.budget = timeoutMs * 1_000_000L;
    }

    void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CollectionException(CollectionException.Code.INTERRUPTED, "response", 0, "request");
        }
        if (clock.getAsLong() - start >= budget) {
            throw new CollectionException(CollectionException.Code.TIMEOUT, "response", 0, "request");
        }
    }

    long remainingNanos() {
        check();
        return Math.max(1, budget - (clock.getAsLong() - start));
    }

    double elapsedMs() {
        check();
        double result = (clock.getAsLong() - start) / 1_000_000.0;
        if (!Double.isFinite(result) || result < 0) {
            throw new CollectionException(CollectionException.Code.LOCAL, "clock", 0, "latencyMs");
        }
        return result;
    }
}
