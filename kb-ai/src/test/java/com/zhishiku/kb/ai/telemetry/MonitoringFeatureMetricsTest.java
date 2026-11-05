package com.zhishiku.kb.ai.telemetry;

import com.zhishiku.kb.ai.observation.AiObservationProperties;
import com.zhishiku.kb.ai.conversation.config.ConversationProperties;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoringFeatureMetricsTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void exportsBoundLocalValuesWithoutMergingPeerState(boolean conversation, boolean observation) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class, PrometheusMetricsExportAutoConfiguration.class))
                .withUserConfiguration(Properties.class, MonitoringFeatureMetrics.class)
                .withPropertyValues("kb.conversation.enabled=" + conversation, "kb.ai.observability.enabled=" + observation,
                        "kb.ai.observability.secret=0123456789abcdef0123456789abcdef")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var registry = context.getBean(PrometheusMeterRegistry.class);
                    assertThat(registry.scrape()).contains(
                            "kb_monitoring_feature_enabled{feature=\"conversation\"} " + (conversation ? "1.0" : "0.0"),
                            "kb_monitoring_feature_enabled{feature=\"ai_observation\"} " + (observation ? "1.0" : "0.0"));
                    assertThat(registry.find("kb.monitoring.feature.enabled").gauges()).hasSize(2).allSatisfy(gauge -> {
                        assertThat(gauge.getId().getTags()).hasSize(1);
                        assertThat(gauge.getId().getTags().getFirst().getKey()).isEqualTo("feature");
                    });
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ConversationProperties.class, AiObservationProperties.class})
    static class Properties {
    }
}
