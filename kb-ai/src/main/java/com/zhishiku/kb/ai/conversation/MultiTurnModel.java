package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import com.zhishiku.kb.ai.budget.BudgetCapability;
import com.zhishiku.kb.ai.budget.BudgetKind;
import com.zhishiku.kb.ai.budget.InputTokenCount;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 多轮适配器必须明确提供预算类型，不回退旧单轮占位实现。
 */
public interface MultiTurnModel {
    /**
     * 旧严格上界能力；估算实现返回 false，新编排使用 budgetCapability 判断就绪。
     */
    boolean supportsTokenUpperBound();

    /**
     * 模型总窗口。
     */
    long contextWindowTokens();

    /**
     * 包含消息包装、角色和材料格式化成本的输入 token 上界。
     */
    long inputTokenUpperBound(Input input);

    /** 兼容原严格扩展；未支持上界时不会读取窗口或计算计数。 */
    default BudgetCapability budgetCapability() {
        if (!supportsTokenUpperBound()) {
            return BudgetCapability.unavailable();
        }
        return new BudgetCapability(BudgetKind.VERIFIED, contextWindowTokens(), 0);
    }

    /** 新编排使用带类型的计数；估算实现必须覆盖此入口。 */
    default InputTokenCount countInputTokens(Input input) {
        if (!supportsTokenUpperBound()) {
            throw new IllegalStateException("模型预算不可用");
        }
        return new InputTokenCount(BudgetKind.VERIFIED, inputTokenUpperBound(input));
    }

    Flux<Event> generate(Input input);

    /**
     * 有序角色消息。
     *
     * @param role user 或 assistant
     * @param text 原消息文本
     */
    record Message(String role, String text) {
    }

    /**
     * 实际模型输入。
     *
     * @param instruction     固定系统指令
     * @param messages        有序完整消息
     * @param materials       实际传入的新材料
     * @param maxOutputTokens 输出上限
     */
    record Input(String instruction, List<Message> messages, List<RetrievalClient.Citation> materials,
                 int maxOutputTokens) {
        public Input {
            messages = List.copyOf(messages);
            materials = List.copyOf(materials);
        }
    }

    /**
     * 模型增量及累计最终统计，统计事件不累加。
     *
     * @param text             文本增量
     * @param finalUsage       是否确认最终累计用量
     * @param promptTokens     累计输入量
     * @param completionTokens 累计输出量
     */
    record Event(String text, boolean finalUsage, Long promptTokens, Long completionTokens) {
    }
}

