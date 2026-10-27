package com.zhishiku.kb.ai.observation;

import com.zhishiku.kb.common.aiusage.AiObservationSettings;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * 生成端独立观测配置；不依赖多轮会话开关。
 */
@ConfigurationProperties(prefix = "kb.ai.observability")
public class AiObservationProperties extends AiObservationSettings {
    private final Environment environment;
    @Autowired
    public AiObservationProperties(Environment environment) { this.environment = environment; }
    @PostConstruct
    void check() { validate(!Arrays.asList(environment.getActiveProfiles()).contains("dev")); }
}
