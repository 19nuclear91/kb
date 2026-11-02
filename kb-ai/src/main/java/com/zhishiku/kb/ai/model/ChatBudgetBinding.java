package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;

import java.util.List;

/** 一个模型身份至多绑定一个预算策略；缺少策略明确不可用。 */
final class ChatBudgetBinding implements ChatBudgetPolicy {
    private final ChatModelIdentity identity;
    private final ChatTokenBudget policy;
    private final long window;

    ChatBudgetBinding(ChatModelIdentity identity, List<ChatTokenBudget> policies) {
        this.identity = identity;
        try {
            List<ChatTokenBudget> matching = policies.stream()
                    .filter(candidate -> identity.equals(candidate.identity())).toList();
            if (matching.size() > 1) {
                throw ChatModelProperties.invalid();
            }
            policy = matching.isEmpty() ? null : matching.getFirst();
            window = policy == null ? 0 : policy.contextWindowTokens();
            if (policy != null && window <= 0) {
                throw ChatModelProperties.invalid();
            }
        } catch (RuntimeException error) {
            throw ChatModelProperties.invalid();
        }
    }

    boolean available() {
        return policy != null;
    }

    long window() {
        return window;
    }

    @Override
    public BudgetCapability capability() {
        return available() ? new BudgetCapability(BudgetKind.VERIFIED, window, 0)
                : BudgetCapability.unavailable();
    }

    @Override
    public InputTokenCount count(PreparedChatRequest request) {
        return new InputTokenCount(BudgetKind.VERIFIED, upperBound(request));
    }

    long upperBound(PreparedChatRequest request) {
        try {
            if (policy == null || !identity.equals(policy.identity())
                    || !identity.model().equals(request.model())
                    || !identity.formatVersion().equals(request.formatVersion())) {
                throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
            }
            long result = policy.inputTokenUpperBound(request);
            if (result < 0) {
                throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
            }
            return result;
        } catch (RuntimeException error) {
            throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
        }
    }
}
