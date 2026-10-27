package com.zhishiku.kb.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码策略的配置绑定（任务 3.1）。
 *
 * <p>不启动容器：用 Boot 自己的 Binder 直接绑定；环境变量用例走
 * {@link SystemEnvironmentPropertySource}，与生产读 env 的路径一致
 * （KB_AUTH_PASSWORD_MIN_LENGTH 按宽松命名映射到 kb.auth.password.min-length）。
 */
class AuthPasswordPropertiesTest {

    private static AuthPasswordProperties bind(PropertySource<?> source) {
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bindOrCreate("kb.auth.password", Bindable.of(AuthPasswordProperties.class));
    }

    @Test
    void defaultsToEightWhenNotConfigured() {
        assertThat(bind(new MapPropertySource("empty", Map.of())).minLength()).isEqualTo(8);
    }

    @Test
    void canonicalKeyOverridesTheDefault() {
        AuthPasswordProperties properties = bind(new MapPropertySource("app",
                Map.of("kb.auth.password.min-length", "12")));

        assertThat(properties.minLength()).isEqualTo(12);
    }

    @Test
    void environmentVariableStyleKeyOverridesTheDefault() {
        AuthPasswordProperties properties = bind(new SystemEnvironmentPropertySource("env",
                Map.of("KB_AUTH_PASSWORD_MIN_LENGTH", "14")));

        assertThat(properties.minLength()).isEqualTo(14);
    }
}
