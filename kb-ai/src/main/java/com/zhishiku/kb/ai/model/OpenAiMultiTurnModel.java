package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetGuard;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import reactor.core.publisher.Flux;

/** 多轮协议桥接与预算能力分开；严格与估算策略显式选择。 */
public class OpenAiMultiTurnModel implements MultiTurnModel {
    private final ChatPromptRenderer renderer;
    private final OpenAiChatTransport transport;
    private final ChatBudgetPolicy budget;
    private final long reservationTokens;

    OpenAiMultiTurnModel(ChatPromptRenderer renderer, OpenAiChatTransport transport,
                        ChatBudgetPolicy budget, long reservationTokens) {
        this.renderer = renderer;
        this.transport = transport;
        this.budget = budget;
        this.reservationTokens = reservationTokens;
    }

    @Override
    public boolean supportsTokenUpperBound() {
        return budgetCapability().kind() == BudgetKind.VERIFIED;
    }

    @Override
    public long contextWindowTokens() {
        return budgetCapability().windowTokens();
    }

    @Override
    public long inputTokenUpperBound(Input input) {
        if (!supportsTokenUpperBound()) {
            throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
        }
        return countInputTokens(input).tokens();
    }

    @Override
    public BudgetCapability budgetCapability() {
        return budget.capability();
    }

    @Override
    public InputTokenCount countInputTokens(Input input) {
        requireBudget();
        InputTokenCount count = budget.count(renderer.render(input));
        if (count == null || count.kind() != budgetCapability().kind()) {
            throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
        }
        return count;
    }

    @Override
    public Flux<Event> generate(Input input) {
        return ModelStreams.once(() -> {
            requireBudget();
            PreparedChatRequest request = renderer.render(input);
            if (!BudgetGuard.fits(budgetCapability(), budget.count(request), reservationTokens, request.maxOutputTokens())) {
                throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
            }
            return transport.stream(request).map(event -> {
                if (event instanceof ModelEvent.Delta delta) {
                    return new Event(delta.text(), false, null, null);
                }
                if (event instanceof ModelEvent.FinalUsage usage && usage.promptTokens() >= 0
                        && usage.completionTokens() >= 0) {
                    return new Event(null, true, usage.promptTokens(), usage.completionTokens());
                }
                throw new ChatModelFailure(ChatModelFailure.Kind.PROTOCOL_INVALID);
            });
        });
    }

    private void requireBudget() {
        if (budgetCapability() == null || budgetCapability().kind() == BudgetKind.UNAVAILABLE) {
            throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
        }
    }
}
