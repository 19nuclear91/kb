package com.zhishiku.kb.app.notification.model;

/**
 * 派生积压的低基数汇总，不带用户、文档或事件标识。
 *
 * @param pending 待处理事件数
 * @param waitingSource 等待来源解析器的事件数
 * @param oldestAgeSeconds 最老未完成事件距源事件发生的秒数
 */
public record NotificationBacklog(long pending, long waitingSource, long oldestAgeSeconds) {
}
