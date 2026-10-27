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
public class SettlementRow {
    private UUID turnId;
    private long userId;
    private long departmentId;
    private UUID leaseId;
    private UUID eventId;
    private long reservedTokens;
    private String usageStatus;
    private Long promptTokens;
    private Long completionTokens;
    private long chargedTokens;
    private String generationStatus;
    private String intentDigest;
    private String state;
    private int attempts;
    private Instant claimedUntil;
    private Instant nextAttemptAt;
    private Instant createdAt;
    private Instant finalizedAt;
    private Instant settledAt;
}

