package com.zhishiku.kb.gateway.config;

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
import com.zhishiku.kb.gateway.filter.KnowledgeGapResponsePolicy;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关登录路由排除（任务 2.7）与未认证拒绝语义（任务 6.1）。
 *
 * <p>直接构造配置类取 filter，不启动容器、不连 Redis：SaReactorFilter 的
 * include/exclude 是公开字段，断言它们，再用 AntPathMatcher 验证"其余
 * /api/** 仍受保护"的匹配语义。
 *
 * <p>未认证用例直接调用 error 策略，并把 MockServerWebExchange 经
 * SaReactorSyncHolder 放进 Sa-Token 上下文——这正是 SaReactorFilter 在
 * 认证失败时所做的调用（error.run(e) 的返回值经 String.valueOf 写入响应），
 * 只是失败原因由测试构造而非由 Sa-Token 抛出。为此需要临时装配生产启动时
 * 由 starter 完成的两件事：reactor 策略（SaTokenContextRegister 构造器装配）
 * 与线程上下文（SaTokenContextForSpringReactor），以及 JSON 模板（生产由
 * sa-token-jackson 插件装配）——单测没有 Spring 上下文，不装同一套实现就
 * 断言不到真实的拒绝响应。装了什么就在 @AfterEach 还原什么，不让全局静态
 * 状态漏给同 JVM 的其他测试。
 */
class SaTokenGatewayConfigTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final SaReactorFilter filter = new SaTokenGatewayConfig().saReactorFilter();

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
    @DisplayName("登录路由排除，其余 /api/** 仍受保护（任务 2.7）")
    void loginIsExcludedWhileTheRestOfApiStaysProtected() {
        assertThat(filter.includeList).contains("/api/**");
        assertThat(filter.excludeList).isEmpty();

        assertThat(matchesAny(filter.excludeList, "/api/auth/login")).isFalse();
        assertThat(matchesAny(filter.excludeList, "/api/space/documents")).isFalse();
        assertThat(matchesAny(filter.includeList, "/api/space/documents")).isTrue();
        assertThat(matchesAny(filter.excludeList, "/api/admin/embedding/rebuilds")).isFalse();
        assertThat(matchesAny(filter.includeList, "/api/admin/embedding/rebuilds")).isTrue();
    }

    @Test
    @DisplayName("未认证被拒：401 且响应体为 R.fail（任务 6.1）")
    void unauthenticatedFailureGets401WithRFailBody() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/admin/embedding/status").build());
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

    private static boolean matchesAny(List<String> patterns, String path) {
        return patterns.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    @Test
    void gapAuthenticationFailuresUseJson401AndNoStoreWithoutRedis() {
        for (String path : List.of("/api/knowledge-gaps", "/api/knowledge-gaps/1/notes", "/api/admin/knowledge-gaps/1/assign")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            new KnowledgeGapResponsePolicy().filter(exchange, current -> {
                SaReactorSyncHolder.setContext(current);
                try {
                    Assertions.assertThatThrownBy(() -> filter.auth.run(null)).isInstanceOf(NotLoginException.class);
                    Object body = filter.error.run(new NotLoginException(NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null));
                    return current.getResponse().writeWith(Mono.just(current.getResponse().bufferFactory()
                            .wrap(String.valueOf(body).getBytes(StandardCharsets.UTF_8))));
                } finally {
                    SaReactorSyncHolder.clearContext();
                }
            }).block(Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"data\":null").doesNotContain("<html");
        }
    }

    @Test
    void importsAlwaysRequireCurrentSession() {
        for (String path : List.of("/api/document-imports", "/api/document-imports/capabilities",
                "/api/document-imports/resolve", "/api/document-imports/9/items/2/file")) {
            SaReactorSyncHolder.setContext(MockServerWebExchange.from(MockServerHttpRequest.post(path)));
            try {
                Assertions.assertThatThrownBy(() -> filter.auth.run(null)).isInstanceOf(NotLoginException.class);
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }
    }

    @Test
    void browserHandshakeIsPublicButBindingAndMethodVariantsStillNeedSession() {
        for (String[] route : new String[][]{{"GET", "/api/auth/sso/providers"}, {"GET", "/api/auth/sso/wecom" +
                "/callback"},
                {"POST", "/api/auth/sso/feishu/login"}, {"POST", "/api/auth/sso/exchange"}}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(
                    HttpMethod.valueOf(route[0]), route[1]).build());
            SaReactorSyncHolder.setContext(exchange);
            try {
                Assertions.assertThatCode(() -> filter.auth.run(null)).doesNotThrowAnyException();
                assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
                assertThat(exchange.getResponse().getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }
        for (String[] route : new String[][]{{"GET", "/api/auth/sso/bindings"}, {"POST", "/api/auth/sso/wecom" +
                "/bindings"},
                {"GET", "/api/auth/sso/exchange"}, {"POST", "/api/auth/sso/wecom/callback"}}) {
            SaReactorSyncHolder.setContext(MockServerWebExchange.from(MockServerHttpRequest.method(
                    HttpMethod.valueOf(route[0]), route[1]).build()));
            try {
                Assertions.assertThatThrownBy(() -> filter.auth.run(null)).isInstanceOf(NotLoginException.class);
            } finally {
                SaReactorSyncHolder.clearContext();
            }
        }
    }

    @Test
    void corsPreflightDoesNotRequireSessionButActualPatchDoes() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/api/admin/spaces/1")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PATCH").build());
        SaReactorSyncHolder.setContext(exchange);
        try {
            Assertions.assertThatCode(() -> filter.auth.run(null)).doesNotThrowAnyException();
        } finally {
            SaReactorSyncHolder.clearContext();
        }
        SaReactorSyncHolder.setContext(MockServerWebExchange.from(
                MockServerHttpRequest.patch("/api/admin/spaces/1").build()));
        try {
            Assertions.assertThatThrownBy(() -> filter.auth.run(null))
                    .isInstanceOf(NotLoginException.class);
        } finally {
            SaReactorSyncHolder.clearContext();
        }
    }
}
