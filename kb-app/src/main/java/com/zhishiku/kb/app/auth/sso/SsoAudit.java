package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 仅固定枚举进入审计，未知外部用户保留 null 目标。
 */
@Service
public class SsoAudit {
    public static final Set<String> RESULTS = Set.of("OK", "ALREADY_BOUND", "BINDING_REQUIRED", "NOT_MEMBER",
            "USER_DISABLED", "PASSWORD_CHANGE_REQUIRED", "CREDENTIAL_REJECTED", "BINDING_CONFLICT", "INVALID_FLOW",
            "UPSTREAM_UNAVAILABLE", "STORAGE_UNAVAILABLE", "CONFIG_CHANGED");
    private final IdentityAuditService audit;

    public SsoAudit(IdentityAuditService audit) {
        this.audit = audit;
    }

    @Transactional
    public void terminal(String flowId, String intent, String platform, Long user, boolean success, String result) {
        if (!SsoSettings.PLATFORMS.contains(platform)) throw SsoException.invalid();
        AuditScope scope = intent.equals("BIND") ? AuditScope.SSO_BIND : AuditScope.SSO_LOGIN;
        String action = intent.equals("BIND") ? "bind" : "login";
        String fixed = result.equals("SSO_BINDING_REQUIRED") ? "BINDING_REQUIRED" : result;
        if (!RESULTS.contains(fixed)) fixed = "INVALID_FLOW";
        String after = "{\"platform\":\"" + platform + "\",\"resultCode\":\"" + fixed + "\""
                + (intent.equals("BIND") ? ",\"bound\":" + success : "") + "}";
        if (success) audit.success(scope, flowId, user, user, action, "{}", after);
        else audit.failure(scope, flowId, user, user, action, fixed, "{}", after);
    }
}
