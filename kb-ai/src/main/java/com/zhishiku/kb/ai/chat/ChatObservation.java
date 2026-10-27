package com.zhishiku.kb.ai.chat;

import reactor.core.publisher.Mono;

/**
 * 单轮内部观测端口，不改变原 SSE 和旧模型适配边界。
 */
public interface ChatObservation {
    ChatObservation NONE = new ChatObservation() { };
    default Mono<Void> dispatch() { return Mono.empty(); }
    default void result(String code) { }
    default void usage(ChatModelClient.StreamEvent event) { }
    default void completedUsage() { }
}
