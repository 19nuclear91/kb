package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Duration;
import java.time.Instant;

/** 接收和停止各自取保守区间，不比较两台机器的墙上时钟。 */
public record OutputStopWindow(Quality quality, double lowerSeconds, double upperSeconds) {
    public enum Quality {
        CONFIRMED, UNCERTAIN, NOT_LINKED, DEFERRED
    }

    public static OutputStopWindow calculate(boolean linked, boolean initial, Instant commitBefore, Instant commitAfter,
                                             Instant barrierBefore, Instant barrierAfter, Instant checkedAt,
                                             long minNanos, long maxNanos, Instant reportedAt) {
        if (!linked) {
            return unknown(Quality.NOT_LINKED);
        }
        if (!initial) {
            return unknown(Quality.DEFERRED);
        }
        if (commitBefore == null || commitAfter == null || barrierBefore == null || barrierAfter == null
                || checkedAt == null || reportedAt == null || commitAfter.isBefore(commitBefore)
                || barrierAfter.isBefore(barrierBefore) || checkedAt.isAfter(commitAfter)
                || barrierBefore.isBefore(commitBefore) || barrierBefore.isAfter(commitAfter) || barrierAfter.isBefore(commitAfter)
                || reportedAt.isBefore(commitAfter) || minNanos < 0 || maxNanos < minNanos
                || maxNanos > Duration.ofDays(7).toNanos() || maxNanos - minNanos > Duration.ofSeconds(2).toNanos()) {
            return unknown(Quality.UNCERTAIN);
        }
        try {
            Instant earliestStop = checkedAt.plusNanos(minNanos), latestStop = checkedAt.plusNanos(maxNanos);
            if (earliestStop.isBefore(commitAfter) || earliestStop.isAfter(reportedAt)) {
                return unknown(Quality.UNCERTAIN);
            }
            double lower = Duration.between(commitAfter, earliestStop).toNanos() / 1_000_000_000.0;
            double upper = Duration.between(commitBefore, latestStop).toNanos() / 1_000_000_000.0;
            return new OutputStopWindow(Quality.CONFIRMED, lower, upper);
        } catch (RuntimeException inconsistent) {
            return unknown(Quality.UNCERTAIN);
        }
    }

    private static OutputStopWindow unknown(Quality quality) {
        return new OutputStopWindow(quality, 0, 0);
    }
}
