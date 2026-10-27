package com.zhishiku.kb.app.auth.sso;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业浏览器认证配置；不生成会输出密钥的 toString。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.auth.sso", ignoreUnknownFields = false)
public class SsoProperties {
    private String publicOrigin = "";
    private String resultPageUri = "";
    private int stateTtlSeconds = 300;
    private int resultTtlSeconds = 60;
    private int startsPerMinute = 10;
    private int maxActiveFlows = 1000;
    private Map<String, Connection> connections = new LinkedHashMap<>();

    public SsoProperties() {
        for (String id : new String[]{"dingtalk", "wecom", "feishu"}) connections.put(id, new Connection());
    }

    /**
     * 单一企业应用的秘密配置，不作为外部视图返回。
     */
    @Getter
    @Setter
    public static class Connection {
        private boolean enabled;
        private String targetEnterpriseId = "";
        private String clientId = "";
        private String clientSecret = "";
        private String agentId = "";
        private String callbackUri = "";
    }
}
