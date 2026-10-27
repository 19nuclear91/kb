package com.zhishiku.kb.app.auth.sso;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 钉钉个人授权后，再以固定企业应用凭据查询当前有效成员。
 */
@Component
public class DingTalkLoginProvider extends ProviderSupport {
    public DingTalkLoginProvider(SsoSettings settings, SsoHttp http, SsoAppTokens tokens) {
        super(settings, http, tokens);
    }

    @Override
    public String platform() {
        return "dingtalk";
    }

    @Override
    public String authorizationUrl(String state, String verifier) {
        var c = connection();
        return "https://login.dingtalk.com/oauth2/auth?response_type=code&client_id=" + enc(c.getClientId())
                + "&redirect_uri=" + enc(c.getCallbackUri()) + "&scope=openid%20corpid&prompt=consent&corpId="
                + enc(c.getTargetEnterpriseId()) + "&state=" + enc(state);
    }

    @Override
    public VerifiedExternalIdentity verify(Map<String, String> params, String verifier) {
        var c = connection();
        var budget = http.budget();
        String authCode = code(params, "authCode");
        var token = http.call(budget, "POST", "https://api.dingtalk.com/v1.0/oauth2/userAccessToken", Map.of(),
                Map.of("clientId", c.getClientId(), "clientSecret", c.getClientSecret(), "code", authCode,
                        "grantType", "authorization_code"));
        String userToken = text(token, "accessToken");
        if (!c.getTargetEnterpriseId().equals(text(token, "corpId"))) throw SsoException.denied();
        var me = http.call(budget, "GET", "https://api.dingtalk.com/v1.0/contact/users/me",
                Map.of("x-acs-dingtalk-access-token", userToken), null);
        if (me.has("corpId") && !c.getTargetEnterpriseId().equals(text(me, "corpId"))) throw SsoException.denied();
        String union = text(me, "unionId");
        String appToken = tokens.get(platform(), c, () -> {
            var app = http.call(budget, "POST", "https://api.dingtalk.com/v1.0/oauth2/accessToken", Map.of(),
                    Map.of("appKey", c.getClientId(), "appSecret", c.getClientSecret()));
            return new SsoAppTokens.Token(text(app, "accessToken"), number(app, "expireIn"));
        });
        String suffix = "?access_token=" + enc(appToken);
        var mapping = http.call(budget, "POST", "https://oapi.dingtalk.com/topapi/user/getbyunionid" + suffix,
                Map.of(), Map.of("unionid", union));
        check(mapping, "errcode", Set.of(60121L), Set.of());
        String userId = text(mapping.path("result"), "userid");
        var member = http.call(budget, "POST", "https://oapi.dingtalk.com/topapi/v2/user/get" + suffix,
                Map.of(), Map.of("userid", userId));
        check(member, "errcode", Set.of(60121L), Set.of());
        var user = member.path("result");
        if (!userId.equals(text(user, "userid")) || !union.equals(text(user, "unionid"))) throw SsoException.denied();
        if (!flag(user, "active")) throw SsoException.denied();
        return identity(userId);
    }
}
