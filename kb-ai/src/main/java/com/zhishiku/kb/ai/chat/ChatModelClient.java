package com.zhishiku.kb.ai.chat;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Chat 模型最小适配边界。
 *
 * <p>AI 编排只负责把已授权的引用交给它。部署方可注入自定义实现，
 * 或显式启用通用 OpenAI-compatible 适配器；本接口不绑定厂商 SDK。
 */
public interface ChatModelClient {

    /**
     * 返回模型增量文本；完成由 Flux 结束表示。
     */
    Flux<String> stream(String question, List<RetrievalClient.Citation> citations);

    /**
     * 返回带 usage 的模型事件。
     *
     * <p>旧的适配器只实现 {@link #stream(String, List)} 即可继续工作；新的适配器
     * 可以在流尾发送一个 {@link StreamEvent#usage(int, int)}，让配额账本按真实
     * prompt/completion token 结算，而不是按字符数猜测。
     */
    default Flux<StreamEvent> streamEvents(String question,
                                           List<RetrievalClient.Citation> citations) {
        return stream(question, citations).map(StreamEvent::delta);
    }

    /**
     * 模型增量或最终 usage 事件。usage 事件不携带正文。
     *
     * @param text             增量文本；usage 事件为 {@code null}
     * @param promptTokens     输入 token 数；增量事件为 {@code null}
     * @param completionTokens 输出 token 数；增量事件为 {@code null}
     */
    record StreamEvent(String text, Integer promptTokens, Integer completionTokens) {

        public StreamEvent {
            if (promptTokens != null && promptTokens < 0) {
                throw new IllegalArgumentException("prompt token 数不能为负数");
            }
            if (completionTokens != null && completionTokens < 0) {
                throw new IllegalArgumentException("completion token 数不能为负数");
            }
            if (text == null && promptTokens == null && completionTokens == null) {
                throw new IllegalArgumentException("模型事件不能同时为空");
            }
        }

        public static StreamEvent delta(String text) {
            return new StreamEvent(text == null ? "" : text, null, null);
        }

        public static StreamEvent usage(int promptTokens, int completionTokens) {
            return new StreamEvent(null, promptTokens, completionTokens);
        }

        public boolean hasUsage() {
            return promptTokens != null || completionTokens != null;
        }
    }
}
