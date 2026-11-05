package com.zhishiku.notifier;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/** 独立管理子上下文也应用精确路由、端口和 no-store 边界。 */
@ManagementContextConfiguration(proxyBeanMethods = false)
public class NotifierManagementConfiguration {
    @Bean
    FilterRegistrationBean<IngressFilter> notifierManagementIngress(NotifierSettings settings,
            DeliveryService service, NotifierMetrics metrics) {
        return NotifierApplication.registration(settings, service, metrics);
    }
}
