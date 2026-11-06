package com.zhishiku.kb.common.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** 仅供内部观测；时间锚点来自主库，其余时间量来自同一个 AI 进程的单调时钟。 */
public record OutputStopReport(UUID executionId, long userId, long authEpoch, Instant checkedAt,
                               long afterCheckMinNanos, long afterCheckMaxNanos, String reason) {
    public OutputStopReport {
        if (executionId == null || userId <= 0 || authEpoch <= 0 || checkedAt == null
                || checkedAt.isBefore(Instant.EPOCH) || checkedAt.isAfter(Instant.parse("9999-01-01T00:00:00Z"))
                || afterCheckMinNanos < 0 || afterCheckMaxNanos < afterCheckMinNanos
                || afterCheckMaxNanos > Duration.ofDays(7).toNanos()
                || afterCheckMaxNanos - afterCheckMinNanos > Duration.ofSeconds(2).toNanos()
                || reason == null || !Set.of("ACCOUNT_REVOKED", "AUTHENTICATION_UNAVAILABLE", "OUTPUT_PERMIT_EXPIRED").contains(reason)) {
            throw new IllegalArgumentException("输出停止证据无效");
        }
    }

    @Override
    public String toString() {
        return "OutputStopReport[redacted]";
    }
}
