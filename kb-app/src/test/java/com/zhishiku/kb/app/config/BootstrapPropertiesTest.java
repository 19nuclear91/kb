package com.zhishiku.kb.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引导配置的绑定（任务 5.1）。
 *
 * <p>不启动容器：与 {@code AuthPasswordPropertiesTest} 同法，用 Boot 自己的
 * Binder 直接绑定；环境变量用例走 {@link SystemEnvironmentPropertySource}
 * 并用 {@code StandardEnvironment} 的规范源名，与生产读 env 的路径一致
 * （KB_BOOTSTRAP_ADMIN_USERNAME 按宽松命名映射到 kb.bootstrap.admin.username）。
 *
 * <p>核心断言是"未配置时不启用任何引导行为"：开关是用户名与密码两个凭据，
 * 显示名、密级与部门的默认值不构成开关——否则一次只配了显示名的部署
 * 会试图建出一个没有用户名或密码的管理员。
 */
class BootstrapPropertiesTest {

    private static BootstrapProperties bind(PropertySource<?> source) {
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bindOrCreate("kb.bootstrap", Bindable.of(BootstrapProperties.class));
    }

    @Test
    @DisplayName("未配置：凭据为空白、引导不启用，其余字段落到设计默认值")
    void notConfiguredIsBlankAndDisabled() {
        BootstrapProperties properties = bind(new MapPropertySource("empty", Map.of()));

        assertThat(properties.admin().username()).isBlank();
        assertThat(properties.admin().password()).isBlank();
        assertThat(properties.enabled())
                .as("未配置时不启用任何引导行为：开关是凭据，不是显示名或部门")
                .isFalse();
        assertThat(properties.admin().displayName()).isEqualTo("系统管理员");
        assertThat(properties.admin().clearanceLevel()).isZero();
        assertThat(properties.department().code()).isEqualTo("HQ");
        assertThat(properties.department().name()).isEqualTo("总部");
    }

    @Test
    @DisplayName("只配置用户名：引导仍不启用，缺一不可")
    void usernameAloneKeepsBootstrapDisabled() {
        BootstrapProperties properties = bind(new MapPropertySource("app",
                Map.of("kb.bootstrap.admin.username", "root")));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.admin().username()).isEqualTo("root");
        assertThat(properties.admin().password()).isBlank();
    }

    @Test
    @DisplayName("凭据齐备：引导启用，canonical 键覆盖默认值")
    void credentialsEnableBootstrapAndCanonicalKeysOverrideDefaults() {
        BootstrapProperties properties = bind(new MapPropertySource("app", Map.of(
                "kb.bootstrap.admin.username", "root",
                "kb.bootstrap.admin.password", "initial-secret-1",
                "kb.bootstrap.admin.display-name", "运维管理员",
                "kb.bootstrap.admin.clearance-level", "2",
                "kb.bootstrap.department.code", "DEV",
                "kb.bootstrap.department.name", "研发部")));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.admin().username()).isEqualTo("root");
        assertThat(properties.admin().displayName()).isEqualTo("运维管理员");
        assertThat(properties.admin().clearanceLevel()).isEqualTo((short) 2);
        assertThat(properties.department().code()).isEqualTo("DEV");
        assertThat(properties.department().name()).isEqualTo("研发部");
    }

    @Test
    @DisplayName("环境变量风格键覆盖默认值并启用引导")
    void environmentVariableStyleKeysEnableBootstrap() {
        // 源名必须是 StandardEnvironment 的规范名：Boot 据此选择环境变量专用的
        // 名称映射器。自定义源名下枚举出的键名会被压成无分隔符的乱名——叶子
        // 标量还能直接解析，但嵌套 record 这类对象型参数会因"查不到后代"被
        // 整体置空；引导配置恰好全是嵌套 record，正是踩中的形状。
        BootstrapProperties properties = bind(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                "KB_BOOTSTRAP_ADMIN_USERNAME", "root",
                "KB_BOOTSTRAP_ADMIN_PASSWORD", "initial-secret-1",
                "KB_BOOTSTRAP_DEPARTMENT_CODE", "HQ2")));

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.admin().username()).isEqualTo("root");
        assertThat(properties.department().code()).isEqualTo("HQ2");
        assertThat(properties.department().name())
                .as("未覆盖的字段仍落到设计默认值").isEqualTo("总部");
    }
}
