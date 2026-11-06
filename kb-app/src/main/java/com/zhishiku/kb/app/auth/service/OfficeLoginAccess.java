package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;

/** 每次预览请求和消息都核对原 token 的当前代次，复职后的新登录不能替代原登录。 */
@Service
public class OfficeLoginAccess {
    private final EpochSessionService sessions;
    private final ConversationIdentityGate identity;

    public OfficeLoginAccess(EpochSessionService sessions, ConversationIdentityGate identity) {
        this.sessions = sessions;
        this.identity = identity;
    }

    public long require(String originalToken) {
        try {
            var session = sessions.check(originalToken);
            if (session.mustChangePassword()) {
                throw IdentityException.passwordChangeRequired("必须先完成密码修改");
            }
            identity.require(session.userId());
            return session.userId();
        } catch (ApiException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("预览身份暂不可确认");
        }
    }
}
