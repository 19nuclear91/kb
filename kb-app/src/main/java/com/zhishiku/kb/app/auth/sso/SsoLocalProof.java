package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.LoginThrottle;
import com.zhishiku.kb.app.config.PasswordEncoderConfig;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 复用本地凭据和节流，无 login、会话或本地登录审计副作用。
 */
@Service
public class SsoLocalProof {
    private final IdentityDepartmentMapper mapper;
    private final IdentityService identities;
    private final BCryptPasswordEncoder encoder;
    private final LoginThrottle throttle;

    public SsoLocalProof(IdentityDepartmentMapper mapper, IdentityService identities,
                         BCryptPasswordEncoder encoder, LoginThrottle throttle) {
        this.mapper = mapper;
        this.identities = identities;
        this.encoder = encoder;
        this.throttle = throttle;
    }

    public String verify(long owner, String password) {
        var user = mapper.findIdentity(owner);
        String username = user == null ? "" : user.getUsername();
        if (throttle.isThrottled(username)) throw new SsoException(401, "CREDENTIAL_REJECTED");
        String hash = mapper.findPasswordHashById(owner);
        boolean present = hash != null && !hash.isBlank();
        boolean matches = encoder.matches(password == null ? "" : password,
                present ? hash : PasswordEncoderConfig.DUMMY_PASSWORD_HASH);
        if (password == null || password.isBlank() || !present || !matches || user == null) {
            throttle.recordFailure(username);
            throw new SsoException(401, "CREDENTIAL_REJECTED");
        }
        current(owner, false);
        throttle.clear(username);
        return SsoCrypto.digest(hash);
    }

    public void current(long user, boolean allowPasswordChange) {
        try {
            identities.resolvePrincipal(user);
        } catch (IdentityException disabled) {
            throw new SsoException(403, "USER_DISABLED");
        }
        Boolean mustChange = mapper.findMustChangePasswordById(user);
        if (mustChange == null) throw new SsoException(403, "USER_DISABLED");
        if (!allowPasswordChange && mustChange) throw new SsoException(403, "PASSWORD_CHANGE_REQUIRED");
    }

    public void unchanged(long user, String fingerprint) {
        current(user, false);
        String hash = mapper.findPasswordHashById(user);
        if (hash == null || hash.isBlank() || !SsoCrypto.digest(hash).equals(fingerprint))
            throw new SsoException(403, "CREDENTIAL_REJECTED");
    }
}
