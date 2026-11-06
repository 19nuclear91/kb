package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Component;

/** 企业登录与绑定复用统一认证代次，签发阶段不写 Cookie 或响应头。 */
@Component
public class SsoSession {
    private final EpochSessionService sessions;

    public SsoSession(EpochSessionService sessions) {
        this.sessions = sessions;
    }

    public long require(String token) {
        try {
            return sessions.check(token).userId();
        } catch (ApiException failed) {
            throw safe(failed);
        }
    }

    public void original(SsoFlow flow, String token) {
        if (require(token) != flow.getOwnerUserId() || !SsoCrypto.digest(token).equals(flow.getSessionDigest())) {
            throw new SsoException(403, "INVALID_FLOW");
        }
    }

    public String issue(long user) {
        try {
            return sessions.issue(user, EpochSessionService.Method.SSO);
        } catch (ApiException failed) {
            throw safe(failed);
        }
    }

    public void revoke(String token) {
        sessions.revoke(token);
    }

    private static SsoException safe(ApiException failed) {
        return switch (failed.httpStatus()) {
            case 401 -> new SsoException(401, "CREDENTIAL_REJECTED");
            case 403 -> new SsoException(403, "USER_DISABLED");
            default -> SsoException.storage();
        };
    }
}
