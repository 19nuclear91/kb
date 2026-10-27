package com.zhishiku.kb.ai.conversation;

import com.zhishiku.kb.ai.chat.RetrievalClient;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 新多轮适配器必须明确提供预算上界能力，不回退旧单轮占位实现。
 */
public interface MultiTurnModel {
    /**
     * 是否能验证多轮输入的 token 上界；不支持时入口返回 503。
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

