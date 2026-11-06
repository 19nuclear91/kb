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
public class TurnRow {
    private UUID id;
    private long conversationId;
    private long turnSeq;
    private String requestId;
    private String bindingDigest;
    private String question;
    private String assistantText;
    private String status;
    private String answerKind;
    private boolean sourceSealed;
    private int expectedSourceCount;
    private String sourceDigest;
    private String sealDigest;
    private long lastBatchSeq;
    private int savedCodepoints;
    private String ticketHash;
    private String executionTicketDigest;
    private long executionEpoch;
    private Long authEpoch;
    private String cancellationReason;
    private Instant leaseUntil;
    private boolean modelDispatchIntent;
    private String usageStatus;
    private Long promptTokens;
    private Long completionTokens;
    private boolean scrubbed;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant finishedAt;
}

