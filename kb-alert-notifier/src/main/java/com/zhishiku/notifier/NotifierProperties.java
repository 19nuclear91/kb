package com.zhishiku.notifier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 原始配置只绑定字符串，校验错误不携带原始值或转换异常。 */
@ConfigurationProperties("kb.notifier")
public record NotifierProperties(String environment, String deployment, String catalogFile,
        String bearerSecretFile, String dingtalkTokenFile, String dingtalkSecretFile,
        String grafanaRoot, String adminRoot, String address, String port,
        String managementAddress, String managementPort) {
    @Override
    public String toString() {
        return "NotifierProperties[redacted]";
    }
}
