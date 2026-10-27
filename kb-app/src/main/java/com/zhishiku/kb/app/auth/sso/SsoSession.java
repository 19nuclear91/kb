package com.zhishiku.kb.app.auth.sso;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 隔离 Sa-Token，签发阶段不向响应写 Cookie 或 header。
 */
@Component
public class SsoSession {
    private final StpLogic logic;

    @Autowired
    public SsoSession() {
        this(StpUtil.getStpLogic());
    }

    SsoSession(StpLogic logic) {
        this.logic = logic;
    }

    public long require(String token) {
        if (token == null || token.isBlank() || token.length() > 4096)
            throw new SsoException(401, "CREDENTIAL_REJECTED");
        try {
            Object id = logic.getLoginIdByToken(token);
            if (id == null) throw new SsoException(401, "CREDENTIAL_REJECTED");
            logic.checkActiveTimeout(token);
            long user = Long.parseLong(id.toString());
            if (user <= 0) throw new SsoException(401, "CREDENTIAL_REJECTED");
            return user;
        } catch (SsoException safe) {
            throw safe;
        } catch (NotLoginException invalid) {
            throw new SsoException(401, "CREDENTIAL_REJECTED");
        } catch (NumberFormatException invalid) {
            throw new SsoException(401, "CREDENTIAL_REJECTED");
        } catch (RuntimeException failed) {
            throw SsoException.storage();
        }
    }

    public void original(SsoFlow f, String token) {
        if (require(token) != f.getOwnerUserId() || !SsoCrypto.digest(token).equals(f.getSessionDigest()))
            throw new SsoException(403, "INVALID_FLOW");
    }

    public String issue(long user) {
        String candidate = SsoCrypto.random();
        try {
            var config = logic.getConfigOrGlobal();
            long timeout = config.getTimeout() > 0 ? config.getTimeout() : 2592000;
            return logic.createLoginSession(user, new SaLoginParameter(config).setToken(candidate).setTimeout(timeout)
                    .setIsConcurrent(true).setIsShare(false).setMaxLoginCount(-1).setIsWriteHeader(false));
        } catch (RuntimeException unavailable) {
            try {
                logic.logoutByTokenValue(candidate);
            } catch (RuntimeException failed) { /* 未交付令牌仍受有限 TTL 约束。 */ }
            throw SsoException.storage();
        }
    }

    public void revoke(String token) {
        logic.logoutByTokenValue(token);
    }
}
