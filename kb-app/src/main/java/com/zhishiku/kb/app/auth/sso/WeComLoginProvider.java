package com.zhishiku.kb.app.auth.sso;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 企业自建应用网页扫码，明确 UserId 并读取企业成员状态。
 */
@Component
public class WeComLoginProvider extends ProviderSupport {
    public WeComLoginProvider(SsoSettings settings, SsoHttp http, SsoAppTokens tokens) {
        super(settings, http, tokens);
    }

    @Override
    public String platform() {
        return "wecom";
    }

    @Override
    public String authorizationUrl(String state, String verifier) {
        var c = connection();
        return "https://login.work.weixin.qq.com/wwlogin/sso/login?login_type=CorpApp&appid="
                + enc(c.getTargetEnterpriseId()) + "&agentid=" + enc(c.getAgentId())
                + "&redirect_uri=" + enc(c.getCallbackUri()) + "&state=" + enc(state);
    }

    @Override
    public VerifiedExternalIdentity verify(Map<String, String> params, String verifier) {
        var c = connection();
        var budget = http.budget();
        String authCode = code(params, "code");
        String appToken = tokens.get(platform(), c, () -> {
            var app = http.call(budget, "GET", "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid="
                    + enc(c.getTargetEnterpriseId()) + "&corpsecret=" + enc(c.getClientSecret()), Map.of(), null);
            check(app, "errcode", Set.of(), Set.of());
            return new SsoAppTokens.Token(text(app, "access_token"), number(app, "expires_in"));
        });
        var me = http.call(budget, "GET", "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token="
                + enc(appToken) + "&code=" + enc(authCode), Map.of(), null);
        check(me, "errcode", Set.of(60111L), Set.of(40029L, 40163L));
        if (!me.has("userid")) {
            if (me.has("openid")) throw SsoException.denied();
            throw SsoException.unavailable();
        }
        String userId = text(me, "userid");
        var member = http.call(budget, "GET", "https://qyapi.weixin.qq.com/cgi-bin/user/get?access_token="
                + enc(appToken) + "&userid=" + enc(userId), Map.of(), null);
        check(member, "errcode", Set.of(60111L), Set.of());
        if (!userId.equals(text(member, "userid"))) throw SsoException.denied();
        if (number(member, "status") != 1) throw SsoException.denied();
        return identity(userId);
    }
}
