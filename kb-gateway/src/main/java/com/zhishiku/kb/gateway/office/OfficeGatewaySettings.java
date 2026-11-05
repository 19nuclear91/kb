package com.zhishiku.kb.gateway.office;

import com.zhishiku.kb.common.office.OfficeProtocol;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.office-preview")
public class OfficeGatewaySettings implements InitializingBean {
    private boolean enabled;
    private String protocol = OfficeProtocol.ADAPTER_VERSION;
    private String applicationOrigin = "";
    private String previewOrigin = "";
    private String appBaseUrl = "";
    private String upstreamUrl = "";
    private String gatewaySecret = "";
    private int authorizationTimeoutSeconds = 3;
    private int upstreamTimeoutSeconds = 30;
    private int maxConnections = 100;
    private int maxMessageBytes = 4 * 1024 * 1024;
    private long maxResourceBytes = 100L * 1024 * 1024;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        try {
            URI application = base(applicationOrigin, true);
            URI preview = base(previewOrigin, true);
            base(appBaseUrl, false);
            base(upstreamUrl, false);
            if (application.getHost().equalsIgnoreCase(preview.getHost()) || gatewaySecret.length() < 32
                    || !OfficeProtocol.ADAPTER_VERSION.equals(protocol)
                    || authorizationTimeoutSeconds < 1 || authorizationTimeoutSeconds > 10
                    || upstreamTimeoutSeconds < 5 || upstreamTimeoutSeconds > 60
                    || maxConnections < 1 || maxConnections > 1000
                    || maxMessageBytes < 16384 || maxMessageBytes > 8 * 1024 * 1024
                    || maxResourceBytes < 1 || maxResourceBytes > 200L * 1024 * 1024) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Office 预览网关配置无效");
        }
    }

    private static URI base(String value, boolean https) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty() || !(https ? "https".equals(uri.getScheme()) : Set.of("http", "https").contains(uri.getScheme()))) {
            throw new IllegalArgumentException();
        }
        return uri;
    }
}
