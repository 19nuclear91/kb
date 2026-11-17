package com.zhishiku.kb.answereval;

import java.util.function.LongSupplier;

/** 可注入单调时钟的整阶段期限。 */
public final class Deadline {
    private final LongSupplier clock;
    private final long start;
    private final long budget;

    public Deadline(LongSupplier clock, int milliseconds) {
        Checks.require(milliseconds > 0 && milliseconds <= 600_000);
        this.clock = clock;
        start = clock.getAsLong();
        budget = milliseconds * 1_000_000L;
    }

    public void check() {
        LocalFiles.checkInterrupted();
        if (clock.getAsLong() - start >= budget) {
            throw new EvaluationFailure(EvaluationFailure.Code.DEADLINE);
        }
    }

    public long remainingNanos() {
        check();
        return Math.max(1, budget - (clock.getAsLong() - start));
    }
}
