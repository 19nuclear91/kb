package com.zhishiku.kb.ai.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.fun.strategy.SaCreateSaRequestFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaResponseFunction;
import cn.dev33.satoken.fun.strategy.SaCreateSaStorageFunction;
import cn.dev33.satoken.fun.strategy.SaRouteMatchFunction;
import cn.dev33.satoken.json.SaJsonTemplate;
import cn.dev33.satoken.json.SaJsonTemplateForJackson;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.reactor.spring.SaTokenContextForSpringReactor;
import cn.dev33.satoken.reactor.spring.SaTokenContextRegister;
import cn.dev33.satoken.strategy.SaStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.api.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kb-ai 未认证拒绝语义（任务 6.3）：401 且响应体为 R.fail。
 *
 * <p>与网关 {@code SaTokenGatewayConfigTest} 同构：直接构造配置类取 filter，
 * 不启动容器、不连 Redis；未认证用例直接调用 error 策略，并把
 * MockServerWebExchange 经 SaReactorSyncHolder 放进 Sa-Token 上下文——这正是
 * SaReactorFilter 在认证失败时所做的调用，只是失败原因由测试构造而非由
 * Sa-Token 抛出。为此需要临时装配生产启动时由 starter 完成的两件事：
 * reactor 策略（SaTokenContextRegister 构造器装配）与线程上下文
 * （SaTokenContextForSpringReactor），以及 JSON 模板（生产由 sa-token-jackson
 * 插件装配）。装了什么就在 @AfterEach 还原什么，不让全局静态状态漏给同 JVM
 * 的其他测试。
 */
class SaTokenWebFluxConfigTest {

    private final SaReactorFilter filter = new SaTokenWebFluxConfig().saReactorFilter();

    private SaJsonTemplate originalTemplate;
    private SaTokenContext originalContext;
    private SaRouteMatchFunction originalRouteMatcher;
    private SaCreateSaRequestFunction originalCreateSaRequest;
    private SaCreateSaResponseFunction originalCreateSaResponse;
    private SaCreateSaStorageFunction originalCreateSaStorage;

    @BeforeEach
    void installSaTokenStatics() {
        originalTemplate = SaManager.getSaJsonTemplate();
        originalContext = SaManager.getSaTokenContext();
        originalRouteMatcher = SaStrategy.instance.routeMatcher;
        originalCreateSaRequest = SaStrategy.instance.createSaRequest;
        originalCreateSaResponse = SaStrategy.instance.createSaResponse;
        originalCreateSaStorage = SaStrategy.instance.createSaStorage;

        SaManager.setSaJsonTemplate(new SaJsonTemplateForJackson());
        new SaTokenContextRegister();
        SaManager.setSaTokenContext(new SaTokenContextForSpringReactor());
    }

    @AfterEach
    void restoreSaTokenStatics() {
        SaManager.setSaJsonTemplate(originalTemplate);
        SaManager.setSaTokenContext(originalContext);
        SaStrategy.instance.routeMatcher = originalRouteMatcher;
        SaStrategy.instance.createSaRequest = originalCreateSaRequest;
        SaStrategy.instance.createSaResponse = originalCreateSaResponse;
        SaStrategy.instance.createSaStorage = originalCreateSaStorage;
    }

    @Test
    @DisplayName("未认证被拒：401 且响应体为 R.fail（任务 6.3）")
    void unauthenticatedFailureGets401WithRFailBody() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/chat/stream").build());
        SaReactorSyncHolder.setContext(exchange);
        try {
            Object body = filter.error.run(new NotLoginException(
                    NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null));

            assertThat(exchange.getResponse().getStatusCode())
                    .as("未认证是 401（会话缺失或失效），403 留给已认证但无资格")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getHeaders().getContentType())
                    .isEqualTo(MediaType.APPLICATION_JSON);

            assertThat(body).isInstanceOf(String.class);
            JsonNode json = new ObjectMapper().readTree((String) body);
            assertThat(json.path("code").asInt()).isEqualTo(R.FAILURE);
            assertThat(json.path("message").asText()).isEqualTo("登录状态无效或已过期");
            assertThat(json.path("data").isNull()).isTrue();
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }
}
