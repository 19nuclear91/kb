package com.zhishiku.kb.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主应用 OpenAPI 文档元信息。Knife4j 页面地址为 {@code /doc.html}。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI kbAppOpenApi() {
        return new OpenAPI().info(new Info()
                .title("企业智能知识库 API")
                .version("0.1.0")
                .description("文档上传、权限检索、索引管理以及受保护的身份/部门生命周期接口；管理写入使用版本字段和 X-Request-Id 幂等键"));
    }
}
