package com.zhishiku.kb.common.embedding;

/** 查询调用显式传入的纯 JDK 预算；不属于模型语义或 profile 指纹。 */
public interface CallBudget {
    long remainingNanos();

    int maxResponseBytes();

    void check();

    Registration onCancel(Runnable action);

    Registration limitTo(long nanos);

    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
