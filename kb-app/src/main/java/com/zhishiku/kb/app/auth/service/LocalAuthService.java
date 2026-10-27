package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.LocalCredentialRecord;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.model.SessionIdentity;
import com.zhishiku.kb.app.config.AuthPasswordProperties;
import com.zhishiku.kb.app.config.PasswordEncoderConfig;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.IdentitySummary;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 本地凭据：用户名密码登录与自助改密（设计 D3/D4/D5）。
 *
 * <p>检查顺序是安全属性的一部分：先查节流（被拒尝试不落审计、不查库），
 * 再取凭据；未知用户与空哈希都先做一次假哈希比对再拒绝，让"用户不存在"
 * 与"密码错误"耗时相当；停用状态只在凭据验证通过后才区分，不构成枚举面。
 * 会话建立复用 {@link IdentityService#resolvePrincipal}，与将来的钉钉回调
 * 走同一入口。
 */
@Service
public class LocalAuthService {

    /**
     * 凭据失败的统一文案：未知用户、密码错、空哈希、节流拒绝共用它。
     */
    static final String CREDENTIAL_FAILURE_MESSAGE = "用户名或密码错误";

    private final IdentityDepartmentMapper mapper;
    private final IdentityService identityService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final LoginThrottle throttle;
    private final LoginAuditService audit;
    private final IdentityAuditService identityAudit;
    private final AuthPasswordProperties passwordProperties;

    public LocalAuthService(IdentityDepartmentMapper mapper,
                            IdentityService identityService,
                            BCryptPasswordEncoder passwordEncoder,
                            LoginThrottle throttle,
                            LoginAuditService audit,
                            IdentityAuditService identityAudit,
                            AuthPasswordProperties passwordProperties) {
        this.mapper = mapper;
        this.identityService = identityService;
        this.passwordEncoder = passwordEncoder;
        this.throttle = throttle;
        this.audit = audit;
        this.identityAudit = identityAudit;
        this.passwordProperties = passwordProperties;
    }

    /**
     * 校验凭据并建立 Sa-Token 会话。
     *
     * @return 扩展后的登录结果：会话令牌、强制改密标记与用户摘要
     */
    public LoginResult login(String username, String rawPassword) {
        String normalizedUsername = username == null ? "" : username;
        String password = rawPassword == null ? "" : rawPassword;

        if (throttle.isThrottled(normalizedUsername)) {
            // 与凭据失败完全一致，不暴露节流状态；被拒尝试不逐条写审计
            throw IdentityException.unauthorized(CREDENTIAL_FAILURE_MESSAGE);
        }
        LocalCredentialRecord credential = mapper.findIdentityByUsername(normalizedUsername);
        if (credential == null || isBlankHash(credential.getPasswordHash())) {
            passwordEncoder.matches(password, PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
            rejectCredential(normalizedUsername, credential == null ? null : credential.getId());
        }
        if (!passwordEncoder.matches(password, credential.getPasswordHash())) {
            rejectCredential(normalizedUsername, credential.getId());
        }
        try {
            identityService.resolvePrincipal(credential.getId());
        } catch (IdentityException rejected) {
            // 凭据正确后才区分停用；这不是凭据失败，不计入节流
            audit.failure(normalizedUsername, credential.getId(),
                    "登录被拒绝：" + rejected.errorCode());
            throw rejected;
        }
        StpUtil.login(credential.getId());
        throttle.clear(normalizedUsername);
        audit.success(credential.getId(), normalizedUsername);
        return LoginResult.builder()
                .tokenName(AuthHeaders.SA_TOKEN)
                .tokenValue(StpUtil.getTokenValue())
                .loginId(credential.getId())
                .mustChangePassword(Boolean.TRUE.equals(credential.getMustChangePassword()))
                .user(identityService.get(credential.getId()))
                .build();
    }

    /**
     * 当前会话主体（GET /api/auth/me）：身份、角色与强制改密标记全部由服务端解析，
     * 客户端携带的任何身份声明都不参与。
     */
    public SessionIdentity sessionIdentity(long userId) {
        IdentitySummary user = identityService.get(userId);
        Boolean mustChange = mapper.findMustChangePasswordById(userId);
        return new SessionIdentity(user, identityService.resolvePrincipal(userId).roleIds(),
                Boolean.TRUE.equals(mustChange));
    }

    /**
     * 自助改密（设计 D5）。顺序固定：重放检查 → 原密码 → 强度 → 写入 → 审计。
     *
     * <p>requestId 客户端可选，缺省由服务端生成 UUID。重试时携带首次的请求标识
     * 即可命中审计幂等直接返回；否则重试会因为密码已经变更而在"原密码错误"上
     * 被二次拒绝，把一次成功的改密报成失败。
     *
     * <p>原密码错误与强度不足都只读不写：密码、标记与审计均保持不变。
     */
    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword, String requestId) {
        String normalizedRequest = resolveRequestId(requestId);
        if (identityAudit.existing(AuditScope.USER_PASSWORD_CHANGE, normalizedRequest) != null) {
            return;
        }
        String currentHash = mapper.findPasswordHashById(userId);
        if (isBlankHash(currentHash)
                || !passwordEncoder.matches(oldPassword == null ? "" : oldPassword, currentHash)) {
            throw IdentityException.badRequest("原密码错误");
        }
        if (newPassword == null || newPassword.length() < passwordProperties.minLength()) {
            throw IdentityException.badRequest(
                    "新密码长度不能少于 " + passwordProperties.minLength() + " 位");
        }
        Boolean mustChange = mapper.findMustChangePasswordById(userId);
        mapper.updatePassword(userId, passwordEncoder.encode(newPassword), false);
        // 审计明细只记标记状态，不含任何密码或哈希值
        identityAudit.success(AuditScope.USER_PASSWORD_CHANGE, normalizedRequest, userId, userId,
                "change_password",
                "{\"mustChangePassword\":" + Boolean.TRUE.equals(mustChange) + "}",
                "{\"mustChangePassword\":false}");
    }

    private static String resolveRequestId(String requestId) {
        return requestId == null || requestId.isBlank()
                ? UUID.randomUUID().toString() : requestId.trim();
    }

    private void rejectCredential(String username, Long resolvedUserId) {
        throttle.recordFailure(username);
        audit.failure(username, resolvedUserId, "凭据校验失败");
        throw IdentityException.unauthorized(CREDENTIAL_FAILURE_MESSAGE);
    }

    private static boolean isBlankHash(String hash) {
        return hash == null || hash.isBlank();
    }
}
