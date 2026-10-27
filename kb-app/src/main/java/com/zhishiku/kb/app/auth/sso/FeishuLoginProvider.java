package com.zhishiku.kb.app.auth.sso;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 飞书授权码与 S256 PKCE，tenant_key 与应用内 open_id 双重隔离。
 */
@Component
public class FeishuLoginProvider extends ProviderSupport {
    public FeishuLoginProvider(SsoSettings settings, SsoHttp http, SsoAppTokens tokens) {
        super(settings, http, tokens);
    }

    @Override
    public String platform() {
        return "feishu";
    }

    @Override
    public String authorizationUrl(String state, String verifier) {
        var c = connection();
        return "https://accounts.feishu.cn/open-apis/authen/v1/authorize?response_type=code&client_id="
                + enc(c.getClientId()) + "&redirect_uri=" + enc(c.getCallbackUri()) + "&state=" + enc(state)
                + "&code_challenge_method=S256&code_challenge=" + enc(SsoCrypto.challenge(verifier));
    }

    @Override
    public VerifiedExternalIdentity verify(Map<String, String> params, String verifier) {
        var c = connection();
        var budget = http.budget();
        var token = http.call(budget, "POST", "https://open.feishu.cn/open-apis/authen/v2/oauth/token", Map.of(),
                Map.of("grant_type", "authorization_code", "code", code(params, "code"), "client_id", c.getClientId(),
                        "client_secret", c.getClientSecret(), "redirect_uri", c.getCallbackUri(), "code_verifier",
                        verifier));
        check(token, "code", Set.of(), Set.of(20003L, 20004L));
        String userToken = text(token, "access_token");
        var me = http.call(budget, "GET", "https://open.feishu.cn/open-apis/authen/v1/user_info",
                Map.of("Authorization", "Bearer " + userToken), null);
        check(me, "code", Set.of(), Set.of());
        var user = me.path("data");
        if (!c.getTargetEnterpriseId().equals(text(user, "tenant_key"))) throw SsoException.denied();
        String openId = text(user, "open_id");
        String appToken = tokens.get(platform(), c, () -> {
            var app = http.call(budget, "POST", "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal",
                    Map.of(), Map.of("app_id", c.getClientId(), "app_secret", c.getClientSecret()));
            check(app, "code", Set.of(), Set.of());
            return new SsoAppTokens.Token(text(app, "tenant_access_token"), number(app, "expire"));
        });
        var member = http.call(budget, "GET", "https://open.feishu.cn/open-apis/contact/v3/users/" + enc(openId)
                + "?user_id_type=open_id", Map.of("Authorization", "Bearer " + appToken), null);
        check(member, "code", Set.of(41050L), Set.of());
        var current = member.path("data").path("user");
        if (!openId.equals(text(current, "open_id"))) throw SsoException.denied();
        var status = current.path("status");
        if (!flag(status, "is_activated") || flag(status, "is_frozen") || flag(status, "is_resigned")
                || flag(status, "is_exited")) throw SsoException.denied();
        return identity(openId);
    }
}
