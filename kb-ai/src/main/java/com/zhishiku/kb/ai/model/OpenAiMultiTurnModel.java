package com.zhishiku.kb.ai.model;

import com.zhishiku.kb.ai.conversation.MultiTurnModel;
import reactor.core.publisher.Flux;

/** 多轮协议桥接与预算能力分开；缺少核验策略时绝不发送请求。 */
public class OpenAiMultiTurnModel implements MultiTurnModel {
    private final ChatPromptRenderer renderer;
    private final OpenAiChatTransport transport;
    private final ChatBudgetBinding budget;
    private final long reservationTokens;

    OpenAiMultiTurnModel(ChatPromptRenderer renderer, OpenAiChatTransport transport,
                        ChatBudgetBinding budget, long reservationTokens) {
        this.renderer = renderer;
        this.transport = transport;
        this.budget = budget;
        this.reservationTokens = reservationTokens;
    }

    @Override
    public boolean supportsTokenUpperBound() {
        return budget.available();
    }

    @Override
    public long contextWindowTokens() {
        return budget.window();
    }

    @Override
    public long inputTokenUpperBound(Input input) {
        requireBudget();
        return budget.upperBound(renderer.render(input));
    }

    @Override
    public Flux<Event> generate(Input input) {
        return ModelStreams.once(() -> {
            requireBudget();
            PreparedChatRequest request = renderer.render(input);
            long capacity = Math.min(reservationTokens, budget.window());
            long upper = budget.upperBound(request);
            if (request.maxOutputTokens() <= 0 || request.maxOutputTokens() > capacity
                    || upper > capacity - request.maxOutputTokens()) {
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
        if (!budget.available()) {
            throw new ChatModelFailure(ChatModelFailure.Kind.BUDGET_UNAVAILABLE);
        }
    }
}
