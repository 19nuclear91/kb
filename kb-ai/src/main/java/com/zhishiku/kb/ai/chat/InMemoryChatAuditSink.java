package com.zhishiku.kb.ai.chat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 显式开启时才使用的开发审计 sink。
 *
 * <p>生产环境不能因为 Redis sink 未创建就静默降级到内存，否则进程重启会丢失
 * 配额审计；默认配置保持 fail-closed。
 */
@Component
@ConditionalOnProperty(name = "kb.ai.audit-in-memory-enabled", havingValue = "true")
public class InMemoryChatAuditSink implements ChatAuditSink {

    private static final int MAX_EVENTS = 10_000;
    private final Deque<ChatAuditEvent> events = new ArrayDeque<>();

    @Override
    public synchronized void record(ChatAuditEvent event) {
        if (events.size() >= MAX_EVENTS) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    public synchronized int size() {
        return events.size();
    }
}
