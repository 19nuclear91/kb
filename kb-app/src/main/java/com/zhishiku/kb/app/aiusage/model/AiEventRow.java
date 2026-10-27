package com.zhishiku.kb.app.aiusage.model;

import lombok.Data;

import java.time.Instant;

/**
 * 追加事件的内部存储结构，payload 必须经过白名单投影后才能返回。
 */
@Data
public class AiEventRow {
    private long id;
    private String eventId;
    private String callId;
    private String kind;
    private String payloadDigest;
    private Instant occurredAt;
    private Instant recordedAt;
    private String payload;
}
