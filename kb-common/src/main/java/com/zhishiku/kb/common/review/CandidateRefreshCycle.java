package com.zhishiku.kb.common.review;

/**
 * 显式刷新开启新执行周期，旧租约不能提交新周期结果。
 * @param generation 执行周期
 * @param claimToken 租约栅栏
 */
public record CandidateRefreshCycle(long generation, long claimToken) {
    public CandidateRefreshCycle {
        if (generation < 0 || claimToken < 0) throw new IllegalArgumentException("Invalid parse cycle");
    }

    public CandidateRefreshCycle next() {
        return new CandidateRefreshCycle(Math.incrementExact(generation), Math.incrementExact(claimToken));
    }
}
