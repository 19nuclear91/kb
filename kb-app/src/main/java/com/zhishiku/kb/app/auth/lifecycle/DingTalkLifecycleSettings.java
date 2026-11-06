package com.zhishiku.kb.app.auth.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Set;

@ConfigurationProperties("kb.identity.lifecycle.dingtalk")
public record DingTalkLifecycleSettings(boolean enabled, String enterpriseId, String clientId, String clientSecret,
                                        String unifiedAppId, @DefaultValue("dingtalk-internal") String connectionId,
                                        @DefaultValue("INTERNAL") String applicationType,
                                        @DefaultValue("1048576") int maxMessageBytes,
                                        @DefaultValue("1000") int maxEmployees,
                                        @DefaultValue("2") int workers, @DefaultValue("16") int queueCapacity,
                                        @DefaultValue("2000") int timeoutMillis,
                                        @DefaultValue("wss-open-connection.dingtalk.com") Set<String> streamHosts) {
    public DingTalkLifecycleSettings {
        if (maxMessageBytes < 1024 || maxMessageBytes > 1_048_576 || maxEmployees < 1 || maxEmployees > 1000
                || workers < 1 || workers > 4 || queueCapacity < 1 || queueCapacity > 64
                || timeoutMillis < 100 || timeoutMillis > 2000 || streamHosts == null || streamHosts.isEmpty()
                || streamHosts.size() > 4 || streamHosts.stream().anyMatch(host -> host == null
                || !host.matches("[a-z0-9-]+\\.dingtalk\\.com"))) {
            throw new IllegalArgumentException("生命周期接入边界配置无效");
        }
        streamHosts = Set.copyOf(streamHosts);
        if (!"INTERNAL".equals(applicationType) || !valid(connectionId, 64)) {
            throw new IllegalArgumentException("生命周期接入仅支持明确的企业内部应用");
        }
        boolean anyCredential = nonempty(clientId) || nonempty(clientSecret) || nonempty(enterpriseId) || nonempty(unifiedAppId);
        if ((enabled || anyCredential) && (!valid(enterpriseId, 128) || !enterpriseId.startsWith("ding")
                || !valid(clientId, 128) || !valid(clientSecret, 512) || !valid(unifiedAppId, 128))) {
            throw new IllegalArgumentException("生命周期企业应用配置不完整");
        }
    }

    public void requireConfigured() {
        if (!valid(clientSecret, 512)) {
            throw LifecycleFailures.unavailable();
        }
    }

    public String sourceScope() {
        requireConfigured();
        return LifecycleSupport.digest(java.util.Map.of("source", "dingtalk", "enterprise", enterpriseId,
                "application", unifiedAppId));
    }

    public URI requireStreamEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (RuntimeException invalid) {
            throw LifecycleFailures.unavailable();
        }
        if (!"wss".equals(uri.getScheme()) || !streamHosts.contains(uri.getHost()) || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw LifecycleFailures.unavailable();
        }
        return uri;
    }

    private static boolean valid(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.chars().noneMatch(Character::isISOControl) && value.equals(value.trim());
    }

    private static boolean nonempty(String value) {
        return value != null && !value.isEmpty();
    }

    @Override
    public String toString() {
        return "DingTalkLifecycleSettings[enabled=" + enabled + "]";
    }
}
