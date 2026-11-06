package com.zhishiku.kb.app.auth.service;

import java.util.List;

/** 仅在可信服务端保存令牌清理索引，禁止进入日志或 API DTO。 */
public interface SessionTokenIndex {
    record Token(long epoch, String value) {
        @Override
        public String toString() {
            return "SessionToken[redacted]";
        }
    }

    void register(long userId, long epoch, String token, long ttlSeconds);

    List<Token> pending(long userId, long revokedThroughEpoch, int limit);

    void remove(long userId, Token token);
}
