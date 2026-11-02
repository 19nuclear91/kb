package com.zhishiku.kb.ai.budget;

/** 本轮不可变能力声明；不可用时不携带窗口或余量。 */
public record BudgetCapability(BudgetKind kind, long windowTokens, long safetyMarginTokens) {
    public BudgetCapability {
        if (kind == null || safetyMarginTokens < 0
                || (kind == BudgetKind.UNAVAILABLE && (windowTokens != 0 || safetyMarginTokens != 0))
                || (kind != BudgetKind.UNAVAILABLE && windowTokens <= 0)
                || (kind == BudgetKind.VERIFIED && safetyMarginTokens != 0)) {
            throw new IllegalArgumentException("模型预算能力无效");
        }
    }

    public static BudgetCapability unavailable() {
        return new BudgetCapability(BudgetKind.UNAVAILABLE, 0, 0);
    }
}
