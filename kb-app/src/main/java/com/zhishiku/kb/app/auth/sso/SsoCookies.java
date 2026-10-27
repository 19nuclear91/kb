package com.zhishiku.kb.app.auth.sso;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 结果 Cookie 按 flow 命名，旧响应只能写自己的 Cookie。
 */
@Component
public class SsoCookies {
    private final SsoSettings settings;

    public SsoCookies(SsoSettings settings) {
        this.settings = settings;
    }

    public String browserName() {
        return prefix() + "kb-sso-browser";
    }

    public String resultName(String flow) {
        if (flow == null || !flow.matches("[a-f0-9-]{36}")) throw SsoException.invalid();
        return prefix() + "kb-sso-result-" + flow;
    }

    public String read(HttpServletRequest request, String name) {
        String value = null;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies)
            if (name.equals(cookie.getName())) {
                if (value != null) throw SsoException.invalid();
                value = cookie.getValue();
            }
        return value;
    }

    public void write(HttpServletResponse response, String name, String value, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).path("/").httpOnly(true)
                .secure(settings.secureCookies()).sameSite("Lax").maxAge(seconds).build().toString());
    }

    private String prefix() {
        return settings.secureCookies() ? "__Host-" : "";
    }
}
