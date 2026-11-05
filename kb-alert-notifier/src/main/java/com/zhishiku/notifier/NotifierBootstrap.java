package com.zhishiku.notifier;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** 在 WebServer 工厂或日志系统启动前完成专用校验，并封闭端口及遥测配置。 */
public final class NotifierBootstrap implements EnvironmentPostProcessor, Ordered {
    private static final String SOURCE = "notifier-boundary";
    private static final String SETTINGS = "notifier.validated-settings";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        prepare(environment);
    }

    static NotifierSettings prepare(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(SOURCE)) {
            return (NotifierSettings) environment.getPropertySources().get(SOURCE).getProperty(SETTINGS);
        }
        Map<String, Object> aliases = new LinkedHashMap<>();
        for (String name : new String[]{"environment", "deployment", "catalog-file", "bearer-secret-file",
                "dingtalk-token-file", "dingtalk-secret-file", "grafana-root", "admin-root", "address", "port",
                "management-address", "management-port"}) {
            String value = safeProperty(environment, "KB_NOTIFIER_" + name.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            if (value != null) {
                aliases.put("kb.notifier." + name, value);
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("notifier-environment-aliases", aliases));
        NotifierProperties properties;
        try {
            properties = Binder.get(environment).bind("kb.notifier", NotifierProperties.class).orElseThrow(
                    () -> NotifierSettings.config("required"));
        } catch (Exception exception) {
            throw NotifierSettings.config("binding");
        }
        NotifierSettings settings = new NotifierSettings(properties);
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put(SETTINGS, settings);
        fixed.put("server.port", settings.port);
        fixed.put("server.address", settings.address);
        fixed.put("management.server.port", settings.managementPort);
        fixed.put("management.server.address", settings.managementAddress);
        fixed.put("server.servlet.context-path", "/");
        fixed.put("management.server.base-path", "/");
        fixed.put("management.endpoints.web.base-path", "/actuator");
        fixed.put("management.endpoints.enabled-by-default", false);
        fixed.put("management.endpoint.health.enabled", true);
        fixed.put("management.endpoint.prometheus.enabled", true);
        fixed.put("management.endpoints.web.exposure.include", "health,prometheus");
        fixed.put("management.endpoints.web.discovery.enabled", false);
        fixed.put("management.endpoints.jmx.exposure.exclude", "*");
        fixed.put("management.endpoint.health.show-details", "never");
        fixed.put("management.endpoint.health.show-components", "never");
        fixed.put("management.health.defaults.enabled", false);
        fixed.put("management.tracing.enabled", false);
        fixed.put("management.observations.enable.http", false);
        fixed.put("management.metrics.enable.http", false);
        fixed.put("server.forward-headers-strategy", "none");
        fixed.put("server.tomcat.accesslog.enabled", false);
        fixed.put("server.tomcat.threads.max", 16);
        fixed.put("server.tomcat.threads.min-spare", 2);
        fixed.put("server.tomcat.max-connections", 32);
        fixed.put("server.tomcat.accept-count", 2);
        fixed.put("server.tomcat.connection-timeout", "10s");
        fixed.put("server.tomcat.max-swallow-size", 0);
        fixed.put("server.max-http-request-header-size", "8KB");
        fixed.put("server.error.include-message", "never");
        fixed.put("server.error.include-stacktrace", "never");
        fixed.put("server.error.whitelabel.enabled", false);
        fixed.put("spring.mvc.log-request-details", false);
        fixed.put("spring.web.resources.add-mappings", false);
        fixed.put("spring.servlet.multipart.enabled", false);
        fixed.put("spring.jmx.enabled", false);
        fixed.put("logging.level.root", "WARN");
        fixed.put("logging.level.org.springframework.web", "OFF");
        fixed.put("logging.level.org.apache.coyote", "OFF");
        fixed.put("logging.level.org.apache.catalina", "OFF");
        fixed.put("logging.level.org.apache.tomcat", "OFF");
        fixed.put("logging.level.jdk.internal.httpclient", "OFF");
        fixed.put("logging.level.jdk.httpclient", "OFF");
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, fixed));
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        System.clearProperty("jdk.httpclient.HttpClient.log");
        System.setProperty("jdk.internal.httpclient.debug", "false");
        return settings;
    }

    private static String safeProperty(ConfigurableEnvironment environment, String name) {
        try {
            return environment.getProperty(name);
        } catch (Exception exception) {
            throw NotifierSettings.config("binding");
        }
    }
}
