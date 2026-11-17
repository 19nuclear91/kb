package com.zhishiku.kb.ai.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationPropertiesTest {
    @Test
    void disabledConfigurationDoesNotResolveDirectoryOrUsers() {
        var environment = new MockEnvironment()
                .withProperty("kb.ai.evaluation.directory", "//invalid/secret")
                .withProperty("kb.ai.evaluation.allowed-users", "secret");
        var properties = new EvaluationConfiguration().evaluationProperties(environment);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.root()).isNull();
        assertThat(properties.allowedUsers()).isEmpty();
    }

    @Test
    void enabledConfigurationRequiresEveryBoundaryAndRejectsProduction() {
        var environment = new MockEnvironment().withProperty("kb.ai.evaluation.enabled", "true");
        assertThatThrownBy(() -> EvaluationProperties.read(environment))
                .hasMessage("评测采集配置无效").hasNoCause();
        environment.withProperty("kb.ai.evaluation.instance-id", "test-instance")
                .withProperty("kb.ai.evaluation.allowed-users", "1,9223372036854775807")
                .withProperty("kb.ai.evaluation.directory", "target/evaluation-test");
        var properties = EvaluationProperties.read(environment);
        assertThat(properties.allowedUsers()).containsExactlyInAnyOrder(1L, Long.MAX_VALUE);
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> EvaluationProperties.read(environment))
                .hasMessage("评测采集配置无效").hasNoCause();
    }
}
