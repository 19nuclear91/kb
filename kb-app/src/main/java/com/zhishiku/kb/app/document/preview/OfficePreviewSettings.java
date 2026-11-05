package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.common.office.OfficeProtocol;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/** 配置只在服务端使用；禁止将包含秘密的配置对象直接序列化到响应。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.office-preview")
public class OfficePreviewSettings implements InitializingBean {
    private boolean enabled;
    private String protocol = OfficeProtocol.ADAPTER_VERSION;
    private String applicationOrigin = "";
    private String previewOrigin = "";
    private String sourceBaseUrl = "";
    private String configurationSecret = "";
    private String outboxSecret = "";
    private String gatewaySecret = "";
    private String deploymentId = "kb";
    private int launchSeconds = 60;
    private int leaseSeconds = 45;
    private int heartbeatSeconds = 15;
    private int absoluteSeconds = 1800;
    private int maxPerUser = 3;
    private int maxTotal = 100;
    private long maxFileBytes = 50L * 1024 * 1024;

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        try {
            URI application = origin(applicationOrigin);
            URI preview = origin(previewOrigin);
            URI source = URI.create(sourceBaseUrl);
            if (application.getHost().equalsIgnoreCase(preview.getHost())
                    || !Set.of("http", "https").contains(source.getScheme()) || source.getHost() == null
                    || source.getUserInfo() != null || source.getQuery() != null || source.getFragment() != null
                    || !(source.getPath().isEmpty() || "/".equals(source.getPath()))
                    || !OfficeProtocol.ADAPTER_VERSION.equals(protocol)
                    || !deploymentId.matches("[A-Za-z0-9-]{1,64}")) {
                throw new IllegalArgumentException();
            }
            Set<String> secrets = Set.of(configurationSecret, outboxSecret, gatewaySecret);
            if (secrets.size() != 3 || secrets.stream().anyMatch(secret -> secret.length() < 32 || secret.length() > 4096)
                    || launchSeconds < 10 || launchSeconds > 120 || heartbeatSeconds < 5 || heartbeatSeconds > 30
                    || leaseSeconds < heartbeatSeconds * 2 || leaseSeconds > 120
                    || absoluteSeconds < leaseSeconds || absoluteSeconds > 3600
                    || maxPerUser < 1 || maxPerUser > 10 || maxTotal < maxPerUser || maxTotal > 1000
                    || maxFileBytes < 1 || maxFileBytes > 100L * 1024 * 1024) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Office 预览启用配置无效");
        }
    }

    private static URI origin(String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) {
            throw new IllegalArgumentException();
        }
        return uri;
    }
}
