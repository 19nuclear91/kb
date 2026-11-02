package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.InputTokenCount;

/** 内置适配的统一策略入口，严格扩展和估算策略互不回退。 */
interface ChatBudgetPolicy {
    BudgetCapability capability();

    InputTokenCount count(PreparedChatRequest request);
}
