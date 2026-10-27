package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 仅存储在服务端的短期事务；禁止作为控制器返回值或日志参数。
 */
@Getter
@Setter
@NoArgsConstructor
public class SsoFlow {
    private String flowId;
    private String intent;
    private String connectionId;
    private String identityScope;
    private String fingerprint;
    private String browserDigest;
    private String stateDigest;
    private String verifier;
    private long createdAt;
    private long expiresAt;
    private String stage;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long ownerUserId;
    private String sessionDigest;
    private String credentialFingerprint;
    private VerifiedExternalIdentity identity;
    private int errorStatus;
    private String errorCode;
    private String resultDigest;
}
