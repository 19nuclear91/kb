package com.zhishiku.kb.app.document.imports.support;

import com.zhishiku.kb.app.document.imports.service.ImportException;

import java.time.Duration;
import java.util.function.LongSupplier;

/** 本地单调时钟只缩短等待；最终提交资格仍由数据库时间决定。 */
public final class ImportDeadline {
    private final LongSupplier clock;
    private final long started;
    private final long budget;

    public ImportDeadline(Duration budget) {
        this(budget, System::nanoTime);
    }

    public ImportDeadline(Duration budget, LongSupplier clock) {
        this.budget = budget.toNanos();
        if (this.budget <= 0) {
            throw new IllegalArgumentException("Invalid import budget");
        }
        this.clock = clock;
        started = clock.getAsLong();
    }

    public Duration remaining() {
        long left = budget - (clock.getAsLong() - started);
        if (left <= 0 || Thread.currentThread().isInterrupted()) {
            throw new ImportException(ImportException.Reason.UNAVAILABLE);
        }
        return Duration.ofNanos(left);
    }

    public Duration capped(Duration limit) {
        Duration remaining = remaining();
        return remaining.compareTo(limit) < 0 ? remaining : limit;
    }
}
