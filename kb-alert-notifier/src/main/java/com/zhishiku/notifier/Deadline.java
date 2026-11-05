package com.zhishiku.notifier;

import java.time.Duration;
import java.util.function.LongSupplier;

final class Deadline {
    static final Duration TOTAL = Duration.ofSeconds(8);
    private final LongSupplier ticker;
    private final long started;

    Deadline(LongSupplier ticker) {
        this.ticker = ticker;
        started = ticker.getAsLong();
    }

    long remainingNanos() {
        long remaining = TOTAL.toNanos() - (ticker.getAsLong() - started);
        if (remaining <= 0) {
            throw new Rejection(503, Rejection.Reason.timeout);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new Rejection(503, Rejection.Reason.cancelled);
        }
        return remaining;
    }
}
