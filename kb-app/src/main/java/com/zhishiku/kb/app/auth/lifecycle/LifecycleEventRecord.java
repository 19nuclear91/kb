package com.zhishiku.kb.app.auth.lifecycle;

import java.time.Instant;

/** 持久事件身份及内容摘要；员工列表在独立处置项保存。 */
public record LifecycleEventRecord(long id, String sourceScope, String eventId, String eventType,
                                   String enterpriseId, String connectionId, Instant bornAt,
                                   String contentDigest, boolean conflicted, Instant receivedAt) {
    @Override
    public String toString() {
        return "LifecycleEventRecord[conflicted=" + conflicted + "]";
    }
}
