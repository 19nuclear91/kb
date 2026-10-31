package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.common.embedding.CallBudget;

/** 将当前阶段显式传给纯 JDK 客户端，不传播线程局部状态到其他业务线程。 */
public record SearchCallBudget(SearchContext context, int maxResponseBytes) implements CallBudget {
    public SearchCallBudget {
        if (context == null || maxResponseBytes <= 0) {
            throw new IllegalArgumentException("外部调用缺少搜索预算");
        }
    }

    @Override
    public long remainingNanos() {
        return context.window().remainingNanos();
    }

    @Override
    public void check() {
        context.check();
    }

    @Override
    public Registration onCancel(Runnable action) {
        var registration = context.onCancel(action);
        return registration::close;
    }

    @Override
    public Registration limitTo(long nanos) {
        var registration = context.limitTo(nanos);
        return registration::close;
    }
}
