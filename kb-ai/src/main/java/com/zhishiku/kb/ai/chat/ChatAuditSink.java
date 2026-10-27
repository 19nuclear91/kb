package com.zhishiku.kb.ai.chat;

import reactor.core.publisher.Mono;

/**
 * 对话审计的最小适配接口；生产可替换为 Redis、消息队列或审计服务。
 */
public interface ChatAuditSink {

    void record(ChatAuditEvent event);

    /**
     * 可等待的持久化入口。旧的 sink 只实现 {@link #record(ChatAuditEvent)} 也能工作，
     * 新的持久化实现应覆盖此方法以返回真实写入结果。
     */
    default Mono<Void> recordAsync(ChatAuditEvent event) {
        return Mono.fromRunnable(() -> record(event));
    }
}
