package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;

/** utf8-budget-v1：纯本地字节启发式，不实现严格 ChatTokenBudget 契约。 */
final class ConfiguredEstimatedTokenBudget implements ChatBudgetPolicy {
    static final String ALGORITHM = "utf8-budget-v1";
    private final ChatModelIdentity identity;
    private final BudgetCapability capability;
    private final int tokensPerKib;
    private final int perMessageTokens;
    private final int requestOverheadTokens;

    ConfiguredEstimatedTokenBudget(ChatModelIdentity identity, ChatModelProperties.Budget settings) {
        if (identity == null || settings == null || !"estimated".equals(settings.getMode())) {
            throw ChatModelProperties.invalid();
        }
        settings.validateEstimateParameters();
        this.identity = identity;
        capability = new BudgetCapability(BudgetKind.ESTIMATED, settings.getContextWindowTokens(),
                settings.getSafetyMarginTokens());
        tokensPerKib = settings.getTokensPerKib();
        perMessageTokens = settings.getPerMessageTokens();
        requestOverheadTokens = settings.getRequestOverheadTokens();
    }

    @Override
    public BudgetCapability capability() {
        return capability;
    }

    @Override
    public InputTokenCount count(PreparedChatRequest request) {
        try {
            if (!identity.model().equals(request.model()) || !identity.formatVersion().equals(request.formatVersion())) {
                throw unavailable();
            }
            long bytes = 0;
            for (var message : request.messages()) {
                bytes = Math.addExact(bytes, ChatJson.utf8Length(message.role()));
                bytes = Math.addExact(bytes, ChatJson.utf8Length(message.content()));
            }
            return new InputTokenCount(BudgetKind.ESTIMATED, estimate(bytes, request.messages().size()));
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    /** 接受聚合尺寸以便独立验证长整型溢出，无需构造巨量正文。 */
    long estimate(long bytes, long messages) {
        try {
            if (bytes < 0 || messages < 0) {
                throw unavailable();
            }
            long scaled = Math.multiplyExact(bytes, tokensPerKib);
            long rounded = Math.addExact(scaled / 1024, scaled % 1024 == 0 ? 0 : 1);
            return Math.addExact(Math.addExact(rounded, Math.multiplyExact(messages, perMessageTokens)),
                    requestOverheadTokens);
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private static ChatModelFailure unavailable() {
        return new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
    }
}
