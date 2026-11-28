package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.service.health.HealthFailure;
import java.util.function.LongSupplier;

/** 同步读取栈的六秒总预算，不保存跨请求上下文。 */
public final class HealthBudget implements AutoCloseable {
    private static final ThreadLocal<HealthBudget> CURRENT = new ThreadLocal<>();
    private final LongSupplier clock;
    private final long started;

    public HealthBudget() {
        this(System::nanoTime);
    }

    HealthBudget(LongSupplier clock) {
        if (CURRENT.get() != null) {
            throw HealthFailure.unavailable();
        }
        this.clock = clock;
        this.started = clock.getAsLong();
        CURRENT.set(this);
    }

    public static HealthBudget current() {
        return CURRENT.get();
    }

    public long remainingMillis() {
        long remaining = 6_000_000_000L - (clock.getAsLong() - started);
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw HealthFailure.unavailable();
        }
        return Math.max(1, remaining / 1_000_000);
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
