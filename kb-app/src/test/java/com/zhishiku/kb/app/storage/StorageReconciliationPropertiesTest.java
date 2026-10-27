package com.zhishiku.kb.app.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对账配置的绑定（任务 1.3）。
 *
 * <p>不启动容器：用 Boot 自己的 Binder 直接绑定，与 {@code BootstrapPropertiesTest} 同法。
 * 默认值必须与 design D1 表一致；prefix 默认值还必须与上传共用的
 * {@link ObjectKeys#DOCUMENTS_PREFIX} 一致，两处不一致会让对账扫描漏掉正常上传的对象。
 */
class StorageReconciliationPropertiesTest {

    private static StorageReconciliationProperties bind(PropertySource<?> source) {
        Binder binder = new Binder(ConfigurationPropertySources.from(source));
        return binder.bindOrCreate("kb.storage-reconciliation",
                Bindable.of(StorageReconciliationProperties.class));
    }

    @Test
    @DisplayName("未配置：八个字段落到设计默认值，prefix 与上传共用常量一致")
    void emptySourceYieldsDesignDefaults() {
        StorageReconciliationProperties properties = bind(new MapPropertySource("empty", Map.of()));

        assertThat(properties.intervalMs()).isEqualTo(3_600_000L);
        assertThat(properties.initialDelayMs()).isEqualTo(300_000L);
        assertThat(properties.orphanGraceSeconds()).isEqualTo(3_600L);
        assertThat(properties.prefix()).isEqualTo("documents/");
        assertThat(properties.prefix())
                .as("默认前缀必须与上传共用同一常量，否则对账扫描范围会漏掉正常上传的对象")
                .isEqualTo(ObjectKeys.DOCUMENTS_PREFIX);
        assertThat(properties.deleteEnabled()).isFalse();
        assertThat(properties.maxDeletesPerCycle()).isEqualTo(500);
        assertThat(properties.missingCheckEnabled()).isTrue();
        assertThat(properties.missingCheckMaxPerCycle()).isEqualTo(100);
    }

    @Test
    @DisplayName("显式配置：八个键全部覆盖默认值")
    void explicitKeysOverrideDefaults() {
        StorageReconciliationProperties properties = bind(new MapPropertySource("app", Map.of(
                "kb.storage-reconciliation.interval-ms", "60000",
                "kb.storage-reconciliation.initial-delay-ms", "5000",
                "kb.storage-reconciliation.orphan-grace-seconds", "7200",
                "kb.storage-reconciliation.prefix", "uploads/",
                "kb.storage-reconciliation.delete-enabled", "true",
                "kb.storage-reconciliation.max-deletes-per-cycle", "50",
                "kb.storage-reconciliation.missing-check-enabled", "false",
                "kb.storage-reconciliation.missing-check-max-per-cycle", "10")));

        assertThat(properties.intervalMs()).isEqualTo(60_000L);
        assertThat(properties.initialDelayMs()).isEqualTo(5_000L);
        assertThat(properties.orphanGraceSeconds()).isEqualTo(7_200L);
        assertThat(properties.prefix()).isEqualTo("uploads/");
        assertThat(properties.deleteEnabled()).isTrue();
        assertThat(properties.maxDeletesPerCycle()).isEqualTo(50);
        assertThat(properties.missingCheckEnabled()).isFalse();
        assertThat(properties.missingCheckMaxPerCycle()).isEqualTo(10);
    }

    @Test
    @DisplayName("上限显式为零：表示该项关闭，而不是回填默认值")
    void zeroCapsMeanDisabledNotDefaults() {
        StorageReconciliationProperties properties = bind(new MapPropertySource("app", Map.of(
                "kb.storage-reconciliation.max-deletes-per-cycle", "0",
                "kb.storage-reconciliation.missing-check-max-per-cycle", "0")));

        assertThat(properties.maxDeletesPerCycle()).isZero();
        assertThat(properties.missingCheckMaxPerCycle()).isZero();
    }
}
