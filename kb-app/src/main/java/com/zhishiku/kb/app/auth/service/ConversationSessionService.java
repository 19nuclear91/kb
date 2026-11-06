package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 仅需要当前用户会话的内部操作使用，执行清理不调用此入口。
 */
@Service
public class ConversationSessionService {
    private final ConversationIdentityGate gate;
    private final EpochSessionService sessions;

    public ConversationSessionService(ConversationIdentityGate gate, EpochSessionService sessions) {
        this.gate = gate;
        this.sessions = sessions;
    }

    public long user(String token) {
        return session(token).userId();
    }

    public EpochSessionService.Session session(String token) {
        try {
            var session = sessions.check(token);
            if (session.mustChangePassword()) {
                throw IdentityException.passwordChangeRequired("请先修改密码");
            }
            gate.require(session.userId());
            return session;
        } catch (ApiException error) {
            throw new ResponseStatusException(org.springframework.http.HttpStatusCode.valueOf(error.httpStatus()),
                    "会话身份不可用");
        }
    }
}

