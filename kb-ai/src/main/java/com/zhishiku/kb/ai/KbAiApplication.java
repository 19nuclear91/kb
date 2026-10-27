package com.zhishiku.kb.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 服务。
 *
 * <p>负责 RAG 编排：改写问题 → 调 {@code kb-app} 的授权检索 → 云端 rerank →
 * 组装 prompt → 调云端 chat → SSE 流式转发给前端，并附引用来源。
 *
 * <p>独立部署的理由（《架构决策记录.md》2.2）：它的负载形态是长连接 + 高 IO 等待，
 * 跟前台的短平快请求完全不同；且模型调用失败率与延迟都远高于内部接口，
 * 混在一起会让前台的超时与熔断策略没法定。
 *
 * <p><b>安全约束</b>：本服务没有数据库连接，且不应当有（见 pom 中的说明）。
 * 语料只能通过 {@code kb-app} 的授权检索接口取得，
 * Sa-Token 会话由本服务和网关校验，并原样透传给 {@code kb-app} 重新执行细粒度权限校验。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KbAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbAiApplication.class, args);
    }
}
