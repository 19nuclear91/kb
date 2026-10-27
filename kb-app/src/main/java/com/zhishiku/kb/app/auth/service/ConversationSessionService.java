package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 仅需要当前用户会话的内部操作使用，执行清理不调用此入口。
 */
@Service
public class ConversationSessionService {
    private final ConversationIdentityGate gate;

    public ConversationSessionService(ConversationIdentityGate gate) {
        this.gate = gate;
    }

    public long user(String token) {
        Object id = token == null ? null : StpUtil.getLoginIdByToken(token);
        if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        try {
            long user = Long.parseLong(id.toString());
            gate.require(user);
            return user;
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
    }
}

