package com.zhishiku.kb.app.aiusage;

import com.zhishiku.kb.common.aiusage.AiObservationSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * app 的独立观测配置，关闭多轮不关闭此能力。
 */
@ConfigurationProperties(prefix = "kb.ai.observability")
public class AiObservationProperties extends AiObservationSettings {
    private final Environment environment;

    @Autowired
    public AiObservationProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void check() {
        validate(!Arrays.asList(environment.getActiveProfiles()).contains("dev"));
    }
}
