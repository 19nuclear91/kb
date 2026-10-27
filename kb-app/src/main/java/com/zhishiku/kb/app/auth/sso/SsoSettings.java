package com.zhishiku.kb.app.auth.sso;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 校验可信同源配置；全关闭时不影响本地认证。
 */
@Component
public class SsoSettings {
    public static final Set<String> PLATFORMS = Set.of("dingtalk", "wecom", "feishu");
    private final SsoProperties properties;
    private final boolean development;

    public SsoSettings(SsoProperties properties, Environment environment) {
        this.properties = properties;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
        validate();
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equals(b.getScheme()) && a.getHost().equals(b.getHost()) && a.getPort() == b.getPort();
    }

    private static void required(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || !value.equals(value.trim()))
            throw new IllegalArgumentException();
    }

    public SsoProperties properties() {
        return properties;
    }

    public boolean secureCookies() {
        return !development || properties.getPublicOrigin().startsWith("https://");
    }

    public List<String> enabled() {
        return properties.getConnections().entrySet().stream().filter(e -> e.getValue().isEnabled())
                .map(e -> e.getKey()).sorted().toList();
    }

    public SsoProperties.Connection connection(String id) {
        var connection = properties.getConnections().get(id);
        if (!PLATFORMS.contains(id) || connection == null || !connection.isEnabled())
            throw new SsoException(404, "CONNECTION_UNAVAILABLE");
        validate();
        return connection;
    }

    public void origin(String origin) {
        if (origin == null || !origin.equals(properties.getPublicOrigin()))
            throw new SsoException(403, "INVALID_FLOW");
    }

    private void validate() {
        try {
            if (!PLATFORMS.containsAll(properties.getConnections().keySet())) throw new IllegalArgumentException();
            if (properties.getStateTtlSeconds() < 30 || properties.getStateTtlSeconds() > 600
                    || properties.getResultTtlSeconds() < 1 || properties.getResultTtlSeconds() > 120
                    || properties.getStartsPerMinute() < 1 || properties.getStartsPerMinute() > 100
                    || properties.getMaxActiveFlows() < 1 || properties.getMaxActiveFlows() > 10000)
                throw new IllegalArgumentException();
            if (enabled().isEmpty()) return;
            URI origin = safeUri(properties.getPublicOrigin());
            if (!origin.getRawPath().isEmpty()) throw new IllegalArgumentException();
            URI result = safeUri(properties.getResultPageUri());
            if (!sameOrigin(origin, result)) throw new IllegalArgumentException();
            for (String id : enabled()) {
                var c = properties.getConnections().get(id);
                required(c.getClientId());
                required(c.getClientSecret());
                required(c.getTargetEnterpriseId());
                if (id.equals("wecom") && !c.getAgentId().matches("[1-9][0-9]{0,18}"))
                    throw new IllegalArgumentException();
                URI callback = safeUri(c.getCallbackUri());
                if (!sameOrigin(origin, callback)
                        || !callback.getRawPath().equals("/api/auth/sso/" + id + "/callback"))
                    throw new IllegalArgumentException();
                if (result.getRawPath().startsWith("/api/")) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("企业 SSO 配置无效");
        }
    }

    private URI safeUri(String text) {
        URI uri = URI.create(text);
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath().contains("%") || text.contains("\\"))
            throw new IllegalArgumentException();
        if (!uri.normalize().equals(uri)) throw new IllegalArgumentException();
        boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(development && loopback && "http".equals(uri.getScheme())))
            throw new IllegalArgumentException();
        return uri;
    }
}
