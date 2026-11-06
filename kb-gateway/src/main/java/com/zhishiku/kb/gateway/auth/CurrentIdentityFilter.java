package com.zhishiku.kb.gateway.auth;

import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationEndpoints;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** 位于 Sa-Token 过滤器之前，异步核对数据库权威身份；原资源授权仍在业务层执行。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class CurrentIdentityFilter implements WebFilter {
    public static final String VERIFIED = CurrentIdentityFilter.class.getName() + ".verified";
    private final AuthenticationClient client;

    public CurrentIdentityFilter(AuthenticationClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();
        if (!(path.equals("/api") || path.startsWith("/api/"))
                || AuthenticationEndpoints.isPublic(method, path) || CorsUtils.isPreFlightRequest(exchange.getRequest())) {
            return chain.filter(exchange);
        }
        return Mono.defer(() -> {
            String token;
            // 复用 Sa-Token 自己的令牌解析规则，避免 header、cookie、参数之间出现两套身份。
            SaReactorSyncHolder.setContext(exchange);
            try {
                token = StpUtil.getTokenValue();
            } finally {
                SaReactorSyncHolder.clearContext();
            }
            return client.check(token);
        }).flatMap(identity -> {
            if (identity.mustChangePassword() && !AuthenticationEndpoints.allowsPasswordChange(method, path)) {
                return Mono.error(IdentityException.passwordChangeRequired("必须先完成密码修改"));
            }
            return Mono.just(identity);
        }).onErrorResume(error -> reject(exchange, error).then(Mono.empty()))
                .flatMap(identity -> {
                    exchange.getAttributes().put(VERIFIED, identity);
                    return chain.filter(exchange);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, Throwable error) {
        int status = error instanceof ApiException known ? known.httpStatus() : 503;
        String message = status == 401 ? "登录状态无效或已过期"
                : status == 403 ? "身份访问被拒绝" : "暂时无法确认身份，请稍后重试";
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setCacheControl("no-store");
        byte[] body = ("{\"code\":500,\"message\":\"" + message + "\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
