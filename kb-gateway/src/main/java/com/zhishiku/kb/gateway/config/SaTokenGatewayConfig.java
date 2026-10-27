package com.zhishiku.kb.gateway.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.SsoPublicEndpoints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.reactive.CorsUtils;

/**
 * 网关统一鉴权。
 *
 * <p>网关只验证 Sa-Token 会话，不解析业务权限；文档和检索的细粒度权限
 * 仍由 kb-app 的权限 SQL 决定。会话通过 Redis 在各服务间共享。
 */
@Configuration
public class SaTokenGatewayConfig {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/api/**")
                .addExclude("/api/auth/dev-login", "/api/auth/login")
                .setAuth(exchange -> {
                    // 预检只验证 CORS 策略，不携带登录凭据；实际请求仍须登录。
                    var current = SaReactorSyncHolder.getExchange();
                    if (current != null && current.getRequest().getPath().value().startsWith("/api/auth/sso/")) {
                        current.getResponse().getHeaders().set("Cache-Control", "no-store");
                        current.getResponse().getHeaders().set("Referrer-Policy", "no-referrer");
                    }
                    if (current != null && SsoPublicEndpoints.matches(current.getRequest().getMethod().name(),
                            current.getRequest().getPath().value())) return;
                    if (current == null || !CorsUtils
                            .isPreFlightRequest(current.getRequest())) {
                        StpUtil.checkLogin();
                    }
                })
                // SaReactorFilter 会把 error 策略的返回值直接写入响应，
                // 这里必须先序列化，不能直接返回 record（否则会写成 record.toString()）。
                .setError(error -> {
                    var exchange = SaReactorSyncHolder.getExchange();
                    if (exchange != null) {
                        // 未认证是 401（会话缺失或失效），不是 403 也不是 500：
                        // 403 留给已认证但无资格（各服务的细粒度权限），
                        // 500 会把一次可预期的拒绝报成服务端故障。
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    }
                    return SaManager.getSaJsonTemplate()
                            .objectToJson(R.fail("登录状态无效或已过期"));
                });
    }
}
