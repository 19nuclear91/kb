package com.zhishiku.kb.ai.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 服务 OpenAPI 文档元信息；Knife4j 页面地址为 {@code /doc.html}。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kbAiOpenApi() {
        return new OpenAPI().info(new Info()
                .title("企业智能知识库 AI API")
                .version("0.1.0")
                .description("SSE 流式问答接口"));
    }
}
