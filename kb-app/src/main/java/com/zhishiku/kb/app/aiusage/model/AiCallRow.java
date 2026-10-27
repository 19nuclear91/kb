package com.zhishiku.kb.app.aiusage.model;

import lombok.Data;

import java.time.Instant;

/**
 * 调用当前事实；仅用于持久化，不直接序列化为公共响应。
 */
@Data
public class AiCallRow {
    private long id;
    private String callId;
    private String registrationDigest;
    private String mode;
    private String turnId;
    private long userId;
    private long departmentId;
    private int protocolVersion;
    private Instant startedAt;
    private Instant initiatedAt;
    private long epoch;
    private long version;
    private String generationStatus;
    private String resultCode;
    private String usageStatus;
    private Long promptTokens;
    private Long completionTokens;
    private boolean quotaIntended;
    private boolean modelDispatchIntent;
    private String leaseId;
    private Long reservedTokens;
    private Long plannedChargedTokens;
    private String settlementPolicy;
    private String settlementStatus;
    private Instant finalizedAt;
    private Instant settledAt;
    private Instant retentionAnchorAt;
    private Instant leaseUntil;
}
