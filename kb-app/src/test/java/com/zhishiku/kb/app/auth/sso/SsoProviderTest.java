package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsoProviderTest {
    @Test
    void dingtalkUsesAuthCodeAndFreshCorporateMapping() {
        var f = fixture("dingtalk", ding());
        var identity = f.provider.verify(Map.of("authCode", "one-use"), "verifier");
        assertThat(identity.subject()).isEqualTo("MemberA");
        assertThat(f.requests).hasSize(5);
        assertThat(f.requests.getFirst()).contains("one-use").doesNotContain("refreshToken");
        assertThat(f.requests.getLast()).contains("/topapi/v2/user/get");
        assertThat(f.provider.authorizationUrl("state", "verifier")).contains("scope=openid%20corpid");
        fails("dingtalk", replace(ding(), 0, "{\"accessToken\":\"ut\",\"corpId\":\"other\"}"), 403);
        fails("dingtalk", replace(ding(), 3, "{\"errcode\":60121}"), 403);
        fails("dingtalk", replace(ding(), 4, "{\"errcode\":0,\"result\":{\"userid\":\"MemberA\"," +
                "\"unionid\":\"union\",\"active\":false}}"), 403);
        fails("dingtalk", replace(ding(), 3, "{\"errcode\":60011,\"errmsg\":\"secret detail\"}"), 503);
        fails("dingtalk", List.of("{\"code\":\"InvalidCode\"}"), 503);
        fails("dingtalk", List.of("not-json"), 503);
        assertThatThrownBy(() -> fixture("dingtalk", List.of()).provider.verify(Map.of("error", "private"), "v"))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.code()).isEqualTo("CREDENTIAL_REJECTED"));
        assertThatThrownBy(() -> fixture("dingtalk", List.of()).provider.verify(Map.of("code", "wrong-field"), "v"))
                .isInstanceOf(SsoException.class);
    }

    @Test
    void wecomRequiresEnterpriseUserIdAndActivatedMember() {
        var f = fixture("wecom", wecom());
        assertThat(f.provider.verify(Map.of("code", "once"), "v").subject()).isEqualTo("MemberA");
        assertThat(f.provider.authorizationUrl("state", "v")).contains("login_type=CorpApp", "agentid=10001");
        fails("wecom", replace(wecom(), 1, "{\"errcode\":0,\"openid\":\"personal\"}"), 403);
        fails("wecom", replace(wecom(), 1, "{\"errcode\":40029}"), 400);
        fails("wecom", replace(wecom(), 1, "{\"errcode\":48002}"), 503);
        fails("wecom", replace(wecom(), 2, "{\"errcode\":0,\"userid\":\"MemberA\"}"), 503);
        fails("wecom", replace(wecom(), 2, "{\"errcode\":0,\"userid\":\"MemberA\",\"status\":2}"), 403);
        fails("wecom", replace(wecom(), 0, "{\"errcode\":-1}"), 503);
    }

    @Test
    void feishuRequiresTenantAppScopeAndExplicitCurrentStatus() {
        var f = fixture("feishu", feishu());
        var identity = f.provider.verify(Map.of("code", "once"), "verifier");
        assertThat(identity.subject()).isEqualTo("OpenA");
        assertThat(f.provider.authorizationUrl("state", "verifier")).contains("code_challenge_method=S256")
                .contains(SsoCrypto.challenge("verifier"));
        assertThat(f.requests.getFirst()).contains("code_verifier", "verifier");
        fails("feishu", replace(feishu(), 1,
                "{\"code\":0,\"data\":{\"tenant_key\":\"other\",\"open_id\":\"OpenA\"}}"), 403);
        fails("feishu", replace(feishu(), 1, "{\"code\":0,\"data\":{\"open_id\":\"OpenA\",\"email\":\"a@example" +
                ".com\",\"mobile\":\"123\"}}"), 503);
        for (String field : new String[]{"is_resigned", "is_frozen", "is_exited"})
            fails("feishu", replace(feishu(), 3, feishu().get(3).replace("\"" + field + "\":false", "\"" + field +
                    "\":true")), 403);
        fails("feishu", replace(feishu(), 3, "{\"code\":99991672}"), 503);
        fails("feishu", replace(feishu(), 3,
                "{\"code\":0,\"data\":{\"user\":{\"open_id\":\"OpenA\",\"status\":{}}}}"), 503);
        var other = SsoSettingsTest.configured("feishu").getConnections().get("feishu");
        other.setClientId("other-app");
        assertThat(identity.provider()).isNotEqualTo(SsoScope.provider("feishu", other));
    }

    private static void fails(String platform, List<String> replies, int status) {
        var f = fixture(platform, replies);
        assertThatThrownBy(() -> f.provider.verify(Map.of(platform.equals("dingtalk") ? "authCode" : "code", "once"),
                "v"))
                .isInstanceOfSatisfying(SsoException.class, e -> assertThat(e.status()).isEqualTo(status))
                .hasMessage("企业认证请求未完成");
    }

    private static Fixture fixture(String id, List<String> replies) {
        Fixture f = new Fixture();
        Queue<String> queue = new ArrayDeque<>(replies);
        var http = new SsoHttp((method, uri, headers, body, timeout) -> {
            f.requests.add(uri.getPath() + (body == null ? "" : new String(body, StandardCharsets.UTF_8)));
            return new SsoHttp.Response(200, queue.remove().getBytes(StandardCharsets.UTF_8));
        }, new ObjectMapper(), System::nanoTime);
        var tokens = mock(SsoAppTokens.class);
        when(tokens.get(anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<SsoAppTokens.Token> load = invocation.getArgument(2);
            var app = load.get();
            assertThat(app.value()).isEqualTo("app-token");
            return app.value();
        });
        var settings = new SsoSettings(SsoSettingsTest.configured(id), new MockEnvironment());
        f.provider = switch (id) {
            case "dingtalk" -> new DingTalkLoginProvider(settings, http, tokens);
            case "wecom" -> new WeComLoginProvider(settings, http, tokens);
            default -> new FeishuLoginProvider(settings, http, tokens);
        };
        return f;
    }

    private static List<String> replace(List<String> values, int index, String value) {
        var copy = new ArrayList<>(values);
        copy.set(index, value);
        return copy;
    }

    private static List<String> ding() {
        return List.of("{\"accessToken\":\"user-token\",\"corpId\":\"corp\",\"refreshToken\":\"discard\"}",
                "{\"unionId\":\"union\"}", "{\"accessToken\":\"app-token\",\"expireIn\":7200}",
                "{\"errcode\":0,\"result\":{\"userid\":\"MemberA\"}}",
                "{\"errcode\":0,\"result\":{\"userid\":\"MemberA\",\"unionid\":\"union\",\"active\":true}}");
    }

    private static List<String> wecom() {
        return List.of("{\"errcode\":0,\"access_token\":\"app-token\",\"expires_in\":7200}",
                "{\"errcode\":0,\"userid\":\"MemberA\"}", "{\"errcode\":0,\"userid\":\"MemberA\",\"status\":1}");
    }

    private static List<String> feishu() {
        return List.of("{\"code\":0,\"access_token\":\"user-token\",\"refresh_token\":\"discard\"}",
                "{\"code\":0,\"data\":{\"tenant_key\":\"corp\",\"open_id\":\"OpenA\"}}",
                "{\"code\":0,\"tenant_access_token\":\"app-token\",\"expire\":7200}",
                "{\"code\":0,\"data\":{\"user\":{\"open_id\":\"OpenA\",\"status\":{\"is_activated\":true," +
                        "\"is_frozen\":false,\"is_resigned\":false,\"is_exited\":false}}}}");
    }

    private static final class Fixture {
        ExternalLoginProvider provider;
        final List<String> requests = new ArrayList<>();
    }
}
