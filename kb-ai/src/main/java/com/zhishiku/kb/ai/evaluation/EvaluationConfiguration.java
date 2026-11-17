package com.zhishiku.kb.ai.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 不依赖业务数据库或 Redis 的本地采集装配。 */
@Configuration(proxyBeanMethods = false)
public class EvaluationConfiguration {
    @Bean(destroyMethod = "close")
    public CaptureRegistry captureRegistry(EvaluationProperties properties) {
        return new CaptureRegistry(properties);
    }

    @Bean
    public EvaluationProperties evaluationProperties(Environment environment) {
        return EvaluationProperties.read(environment);
    }
}
