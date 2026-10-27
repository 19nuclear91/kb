package com.zhishiku.kb.app.auth.sso;

import com.zhishiku.kb.app.auth.model.LoginResult;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standalone MockMvc，无监听端口及应用上下文启动。
 */
class SsoControllerTest {
    private final SsoFacade facade = mock(SsoFacade.class);
    private final SsoSettings settings = new SsoSettings(SsoSettingsTest.configured("feishu"), new MockEnvironment());
    private final SsoCookies cookies = new SsoCookies(settings);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new SsoController(settings, facade, cookies)).build();
    }

    @Test
    void catalogAndSelfStatusOnlyExposeMinimalProjection() throws Exception {
        mvc.perform(get("/api/auth/sso/providers")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].connectionId").value(
                        "feishu"))
                .andExpect(header().string("Cache-Control", "no-store"));
        when(facade.statuses("original")).thenReturn(List.of(new SsoViews.BindingStatus("feishu", "feishu", false,
                true)));
        String body = mvc.perform(get("/api/auth/sso/bindings").header("satoken", "original"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].bound").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("subject", "Digest", "userId", "identityId");
        when(facade.statuses(null)).thenThrow(new SsoException(401, "CREDENTIAL_REJECTED"));
        mvc.perform(get("/api/auth/sso/bindings")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(500));
        mvc.perform(get("/api/auth/sso/bindings?subject=private")).andExpect(status().isBadRequest());
        when(facade.statuses("original")).thenThrow(new SsoException(403, "PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/auth/sso/bindings").header("satoken", "original")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("PASSWORD_CHANGE_REQUIRED"));
    }

    @Test
    void postsRejectOriginUnknownDuplicateFieldsQueryAndWrongMediaType() throws Exception {
        for (String body : new String[]{"{\"userId\":7}", "{\"requestId\":\"client\"}", "{\"x\":1,\"x\":2}", "{} {}",
                "[]"})
            mvc.perform(post("/api/auth/sso/feishu/login").header("Origin", "https://kb.example.com")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/sso/feishu/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/sso/feishu/login").header("Origin", "https://attacker.example")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/sso/feishu/login?returnUrl=https://attacker.example").header("Origin", "https" +
                        "://kb.example.com")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/sso/feishu/login").header("Origin", "https://kb.example.com")
                .contentType(MediaType.TEXT_PLAIN).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/sso/feishu/bindings").header("Origin", "https://kb.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"one\",\"password\":\"two\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(facade);
    }

    @Test
    void startsKnownEnabledConnectionWithSecureBrowserCookie() throws Exception {
        var f = new SsoFlow();
        f.setFlowId(UUID.randomUUID().toString());
        var started = new SsoFlows.Started(f, SsoCrypto.random(), SsoCrypto.random());
        when(facade.start(eq("feishu"), any(), any(), any(), eq(false))).thenReturn(started);
        when(facade.authorization(started)).thenReturn("https://accounts.feishu.cn/authorize?state=opaque");
        var response = mvc.perform(post("/api/auth/sso/feishu/login").header("Origin", "https://kb.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorizationUrl").value("https://accounts.feishu" +
                        ".cn/authorize?state=opaque"))
                .andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains("__Host-kb-sso-browser=", "Secure", "HttpOnly",
                "SameSite=Lax", "Path=/").doesNotContain("Domain");
        for (String id : new String[]{"dingtalk", "unknown"})
            mvc.perform(post("/api/auth/sso/" + id + "/login").header("Origin", "https://kb.example.com")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void callbackUsesPerFlowCookieAndFixedRedirectWithoutOriginalQuery() throws Exception {
        String flow = UUID.randomUUID().toString(), browser = SsoCrypto.random(), result = SsoCrypto.random();
        when(facade.callback(eq("feishu"), eq(browser), any())).thenReturn(new SsoFlows.ResultSecret(flow, result, 60));
        var response = mvc.perform(get("/api/auth/sso/feishu/callback").param("state", "private-state").param("code",
                                "private-code")
                        .cookie(new Cookie(cookies.browserName(), browser))).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", settings.properties().getResultPageUri()))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andReturn().getResponse();
        assertThat(response.getHeader("Set-Cookie")).contains(cookies.resultName(flow), result).doesNotContain(
                "private-code", "private-state", "subject");
        mvc.perform(get("/api/auth/sso/feishu/callback").param("state", "s", "s").param("code", "c")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/sso/feishu/callback").param("state", "s").param("code", "c").param("subject", "s"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/auth/sso/feishu/callback").param("state", "s").param("code", "c").param("error", "e"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exchangeReturnsOriginalLoginShapeAndFixedErrorData() throws Exception {
        var f = new SsoFlow();
        f.setFlowId(UUID.randomUUID().toString());
        String browser = SsoCrypto.random(), result = SsoCrypto.random();
        when(facade.current(browser)).thenReturn(f);
        when(facade.exchange(f, browser, result, null)).thenReturn(LoginResult.builder().tokenName("satoken").tokenValue("new").loginId(7).build());
        mvc.perform(post("/api/auth/sso/exchange").header("Origin", "https://kb.example.com").contentType(MediaType.APPLICATION_JSON)
                        .content("{}").cookie(new Cookie(cookies.browserName(), browser),
                                new Cookie(cookies.resultName(f.getFlowId()), result)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tokenName").value("satoken"))
                .andExpect(jsonPath("$.data.tokenValue").value("new")).andExpect(header().string("Cache-Control", "no" +
                        "-store"));
        for (int http : new int[]{400, 401, 403, 409, 429, 503}) {
            doThrow(new SsoException(http, "FIXED_CODE")).when(facade).exchange(f, browser, result, null);
            var response = mvc.perform(post("/api/auth/sso/exchange").header("Origin", "https://kb.example.com")
                            .contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(new Cookie(cookies.browserName(), browser),
                                    new Cookie(cookies.resultName(f.getFlowId()), result)))
                    .andExpect(status().is(http)).andExpect(jsonPath("$.code").value(500)).andExpect(jsonPath("$.data" +
                            ".errorCode").value("FIXED_CODE"))
                    .andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain(browser, result, "private");
        }
    }
}
