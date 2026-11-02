package com.zhishiku.kb.ai.budget;

/** 带保证级别的输入计数，仅用于准入，不代表实际 usage。 */
public record InputTokenCount(BudgetKind kind, long tokens) {
    public InputTokenCount {
        if (kind == null || kind == BudgetKind.UNAVAILABLE || tokens < 0) {
            throw new IllegalArgumentException("模型输入计数无效");
        }
    }
}
