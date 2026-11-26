package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import java.util.function.LongSupplier;

/** 搜索工作线程的单调总预算；所有 Mapper（包括身份查询）共享剩余时间。 */
public final class AnswerCardSearchBudget implements AutoCloseable {
    private static final ThreadLocal<AnswerCardSearchBudget> CURRENT = new ThreadLocal<>();
    private final LongSupplier clock;
    private final long started;
    private final long allowance;

    public AnswerCardSearchBudget(int millis, LongSupplier clock) {
        this.clock = clock;
        this.started = clock.getAsLong();
        this.allowance = millis * 1_000_000L;
    }

    public void install() {
        CURRENT.set(this);
    }

    public static AnswerCardSearchBudget current() {
        return CURRENT.get();
    }

    public long remainingNanos() {
        long elapsed = clock.getAsLong() - started;
        if (Thread.currentThread().isInterrupted() || elapsed < 0 || elapsed >= allowance) {
            throw AnswerCardFailure.unavailable();
        }
        return allowance - elapsed;
    }

    public int statementMillis() {
        long millis = remainingNanos() / 1_000_000L;
        if (millis < 1) {
            throw AnswerCardFailure.unavailable();
        }
        return (int) Math.min(3000, millis);
    }

    @Override
    public void close() {
        CURRENT.remove();
    }
}
