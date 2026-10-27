package com.zhishiku.kb.app.auth.sso;

/**
 * 外部绑定作用域不包含密钥；在途事务指纹包含完整认证上下文。
 */
public final class SsoScope {
    private SsoScope() {
    }

    public static String application(String id, SsoProperties.Connection c) {
        return id.equals("wecom") ? SsoCrypto.digest(c.getClientId(), c.getAgentId()) : c.getClientId();
    }

    public static String kind(String id) {
        return id.equals("feishu") ? "app-open-id" : "corp-user-id";
    }

    public static String provider(String platform, String enterprise, String app, String kind) {
        return "sso-v1:" + platform + ":" + SsoCrypto.digest(platform, enterprise, app, kind);
    }

    public static String provider(String id, SsoProperties.Connection c) {
        return provider(id, c.getTargetEnterpriseId(), application(id, c), kind(id));
    }

    public static String generation(SsoProperties.Connection c) {
        return SsoCrypto.digest(c.getClientSecret());
    }

    public static String scopes(String id) {
        return id.equals("dingtalk") ? "openid corpid" : "";
    }

    public static String fingerprint(String id, SsoSettings settings) {
        var c = settings.connection(id);
        return SsoCrypto.digest(provider(id, c), c.getCallbackUri(), settings.properties().getPublicOrigin(),
                settings.properties().getResultPageUri(), scopes(id), generation(c));
    }

    public static void matches(String id, SsoProperties.Connection c, VerifiedExternalIdentity identity) {
        if (!provider(id, c).equals(identity.provider())) throw SsoException.denied();
    }
}
