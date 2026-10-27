package com.zhishiku.kb.worker.parse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 第三方解析接入与未配置状态；具体服务由独立适配器提供。
 */
@Configuration(proxyBeanMethods = false)
public class DocumentParserConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(DocumentParser.class)
    DocumentParser unavailableDocumentParser() {
        log.warn("第三方解析服务未配置，暂停领取解析任务");
        return new UnavailableDocumentParser();
    }

    @Bean
    HealthIndicator documentParserHealthIndicator(DocumentParser parser) {
        return () -> parser.isAvailable()
                ? Health.up().build()
                : Health.outOfService().withDetail("reason", "EXTERNAL_PARSER_UNAVAILABLE").build();
    }
}
