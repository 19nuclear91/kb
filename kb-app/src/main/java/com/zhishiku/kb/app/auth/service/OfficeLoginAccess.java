package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 独立预览请求必须验证原登录，不能用同用户的另一个登录替代。 */
@Service
public class OfficeLoginAccess {
    private final StpLogic sessions;
    private final ConversationIdentityGate identity;

    @Autowired
    public OfficeLoginAccess(ConversationIdentityGate identity) {
        this(StpUtil.getStpLogic(), identity);
    }

    public OfficeLoginAccess(StpLogic sessions, ConversationIdentityGate identity) {
        this.sessions = sessions;
        this.identity = identity;
    }

    public long require(String originalToken) {
        if (originalToken == null || originalToken.isBlank() || originalToken.length() > 4096) {
            throw IdentityException.unauthorized("登录状态已失效");
        }
        try {
            Object loginId = sessions.getLoginIdByToken(originalToken);
            if (loginId == null) {
                throw IdentityException.unauthorized("登录状态已失效");
            }
            sessions.checkActiveTimeout(originalToken);
            long userId = Long.parseLong(loginId.toString());
            if (userId <= 0) {
                throw IdentityException.unauthorized("登录状态已失效");
            }
            identity.require(userId);
            return userId;
        } catch (IdentityException expected) {
            throw expected;
        } catch (NotLoginException | NumberFormatException invalid) {
            throw IdentityException.unauthorized("登录状态已失效");
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("预览身份暂不可确认");
        }
    }
}
