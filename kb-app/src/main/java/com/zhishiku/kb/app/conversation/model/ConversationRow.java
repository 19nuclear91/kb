package com.zhishiku.kb.app.conversation.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 会话持久化字段；禁止自动输出正文和资格。
 */
@Getter
@Setter
public class ConversationRow {
    private Long id;
    private long ownerUserId;
    private String title;
    private String createRequestId;
    private String creationDigest;
    private long lastTurnSeq;
    private UUID activeTurnId;
    private long executionEpoch;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
}

