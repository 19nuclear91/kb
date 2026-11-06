package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.AuthenticationIdentity;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.time.Instant;

/** 令牌级可信元数据；旧令牌缺少代次时只能重登，禁止读取时补写。 */
@Service
public class EpochSessionService {
    public enum Method {
        LOCAL, SSO, DEV
    }

    public record Session(long userId, long authEpoch, Method method, boolean mustChangePassword, Instant checkedAt) {
        public Session(long userId, long authEpoch, Method method, boolean mustChangePassword) {
            this(userId, authEpoch, method, mustChangePassword, null);
        }
    }

    public static final String METADATA = "kb.auth.identity.v1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final IdentityDepartmentMapper users;
    private final StpLogic logic;
    private final SessionTokenIndex index;

    @Autowired
    public EpochSessionService(IdentityDepartmentMapper users, SessionTokenIndex index) {
        this(users, StpUtil.getStpLogic(), index);
    }

    public EpochSessionService(IdentityDepartmentMapper users, StpLogic logic, SessionTokenIndex index) {
        this.users = users;
        this.logic = logic;
        this.index = index;
    }

    public String issue(long userId, Method method) {
        if (method == null) {
            throw new IllegalArgumentException("认证方式不能为空");
        }
        AuthenticationIdentity before = current(userId);
        requireEnabled(before);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String candidate = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            var config = logic.getConfigOrGlobal();
            long timeout = config.getTimeout() > 0 ? config.getTimeout() : 2592000;
            String issued = logic.createLoginSession(userId, new SaLoginParameter(config).setToken(candidate)
                    .setTimeout(timeout).setIsConcurrent(true).setIsShare(false).setMaxLoginCount(-1)
                    .setIsWriteHeader(false));
            if (!candidate.equals(issued)) {
                throw new IllegalStateException("未按候选令牌建立会话");
            }
            logic.getTokenSessionByToken(candidate, true).set(METADATA,
                    userId + ":" + before.authEpoch() + ":" + method.name());
            index.register(userId, before.authEpoch(), candidate, timeout);
            AuthenticationIdentity after = current(userId);
            if (before.authEpoch() != after.authEpoch()) {
                throw IdentityException.unauthorized("身份已变化，请重新登录");
            }
            requireEnabled(after);
            return candidate;
        } catch (RuntimeException failed) {
            discard(candidate);
            if (failed instanceof ApiException known) {
                throw known;
            }
            throw unavailable();
        }
    }

    public Session check(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw IdentityException.unauthorized("请重新登录");
        }
        try {
            Object loginId = logic.getLoginIdByToken(token);
            if (loginId == null) {
                throw IdentityException.unauthorized("请重新登录");
            }
            logic.checkActiveTimeout(token);
            long userId = Long.parseLong(loginId.toString());
            Session recorded = recorded(token);
            if (userId <= 0 || recorded == null || recorded.userId() != userId) {
                throw IdentityException.unauthorized("请重新登录");
            }
            AuthenticationIdentity current = current(userId);
            if (recorded.authEpoch() != current.authEpoch()) {
                throw IdentityException.unauthorized("会话已撤销，请重新登录");
            }
            requireEnabled(current);
            return new Session(userId, current.authEpoch(), recorded.method(), current.mustChangePassword(), current.checkedAt());
        } catch (ApiException known) {
            throw known;
        } catch (NotLoginException | NumberFormatException invalid) {
            throw IdentityException.unauthorized("请重新登录");
        } catch (RuntimeException unavailable) {
            throw unavailable();
        }
    }

    /** 供按代次清理使用；读取失败向上传播，不能当作不存在。 */
    public Session recorded(String token) {
        SaSession session = logic.getTokenSessionByToken(token, false);
        Object raw = session == null ? null : session.get(METADATA);
        if (!(raw instanceof String value) || value.length() > 80) {
            return null;
        }
        String[] fields = value.split(":", -1);
        if (fields.length != 3) {
            return null;
        }
        try {
            long userId = Long.parseLong(fields[0]);
            long epoch = Long.parseLong(fields[1]);
            return userId > 0 && epoch > 0 ? new Session(userId, epoch, Method.valueOf(fields[2]), false) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public void revoke(String token) {
        logic.logoutByTokenValue(token);
    }

    public void discard(String token) {
        try {
            revoke(token);
        } catch (RuntimeException failed) {
            // 未交付候选仍受有限 TTL 及请求代次门禁保护，绝不扩大为整用户注销。
        }
    }

    private AuthenticationIdentity current(long userId) {
        try {
            AuthenticationIdentity identity = users.findAuthenticationIdentity(userId);
            if (identity == null) {
                throw IdentityException.unauthorized("请重新登录");
            }
            if (identity.authEpoch() < 1 || identity.userId() != userId) {
                throw unavailable();
            }
            return identity;
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException failed) {
            throw unavailable();
        }
    }

    private static void requireEnabled(AuthenticationIdentity identity) {
        if (!identity.enabled() || !identity.departmentEnabled()) {
            throw IdentityException.disabled("账号或主部门已停用");
        }
    }

    private static ApiException unavailable() {
        return ApiException.unavailable("暂时无法确认身份，请稍后重试");
    }
}
