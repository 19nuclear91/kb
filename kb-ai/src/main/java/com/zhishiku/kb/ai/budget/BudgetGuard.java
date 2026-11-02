package com.zhishiku.kb.ai.budget;

import com.zhishiku.kb.ai.model.ChatModelFailure;

/** 输入选择和实际发送共用容量判定；余量仅在此扣减一次。 */
public final class BudgetGuard {
    private BudgetGuard() {
    }

    public static long inputCapacity(BudgetCapability capability, long reservation, long output) {
        if (capability == null || capability.kind() == BudgetKind.UNAVAILABLE || reservation <= 0 || output <= 0) {
            throw unavailable();
        }
        try {
            long capacity = Math.subtractExact(Math.subtractExact(Math.min(capability.windowTokens(), reservation),
                    output), capability.safetyMarginTokens());
            if (capacity <= 0) {
                throw unavailable();
            }
            return capacity;
        } catch (ArithmeticException error) {
            throw unavailable();
        }
    }

    public static boolean fits(BudgetCapability capability, InputTokenCount count, long reservation, long output) {
        long capacity = inputCapacity(capability, reservation, output);
        if (count == null || count.kind() != capability.kind()) {
            throw unavailable();
        }
        return count.tokens() <= capacity;
    }

    private static ChatModelFailure unavailable() {
        return new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
    }
}
