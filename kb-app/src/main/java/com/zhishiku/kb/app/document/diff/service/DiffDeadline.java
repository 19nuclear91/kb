package com.zhishiku.kb.app.document.diff.service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 超时返回与计算退出共用截止标志，计算不依赖线程强制中断。 */
public final class DiffDeadline {
    private final LongSupplier clock;
    private final long started;
    private final long duration;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public DiffDeadline(long timeoutMs, LongSupplier clock) {
        this.clock = clock;
        this.started = clock.getAsLong();
        this.duration = Math.multiplyExact(timeoutMs, 1_000_000L);
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void check() {
        if (cancelled.get() || clock.getAsLong() - started >= duration || Thread.currentThread().isInterrupted()) {
            throw new Expired();
        }
    }

    public static final class Expired extends RuntimeException {
        public Expired() {
            super("DIFF_TIMEOUT", null, false, false);
        }
    }
}
