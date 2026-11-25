package com.zhishiku.kb.app.auth.service.diagnostics;

import java.util.function.LongSupplier;

/** 仅本调用栈的截止时间；关闭后不保留请求身份或事实。 */
public final class DiagnosticBudget implements AutoCloseable {
    private static final ThreadLocal<DiagnosticBudget> CURRENT = new ThreadLocal<>();
    private final LongSupplier clock;
    private final long deadline;

    DiagnosticBudget(int millis, LongSupplier clock) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("诊断预算不能嵌套");
        }
        this.clock = clock;
        deadline = clock.getAsLong() + millis * 1_000_000L;
        CURRENT.set(this);
    }

    public static DiagnosticBudget current() {
        return CURRENT.get();
    }

    public long remainingMillis() {
        long nanos = deadline - clock.getAsLong();
        if (nanos <= 0 || Thread.currentThread().isInterrupted()) {
            throw new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
        }
        return Math.max(1, nanos / 1_000_000L);
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
