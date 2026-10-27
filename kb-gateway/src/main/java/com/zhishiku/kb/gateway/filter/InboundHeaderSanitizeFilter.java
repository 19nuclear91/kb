package com.zhishiku.kb.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 入站请求头净化。
 *
 * <p><b>这是一个安全边界，不是代码整洁性措施。</b>
 *
 * <p>下游旧版本曾信任身份类请求头（例如 {@code X-Dev-User-Id}）。如果外部调用方能自带这些头，
 * 任何人都能把自己声明成任意用户、任意部门、任意密级——
 * 权限模型做得再严密也当场失效。
 *
 * <p>因此：<b>凡是下游会当作身份来源的头，都必须在此处无条件剥离</b>，
 * 再由网关按服务端验证结果重新注入。剥离发生在路由之前，
 * 顺序为 {@link Ordered#HIGHEST_PRECEDENCE}，不给任何过滤器抢先读到的机会。
 *
 * <p>Sa-Token 的 {@code satoken} 由会话过滤器校验，不能加入剥离列表；
 * 往后每新增一个"下游信任的旧身份头"，都要同步加进 {@link #STRIPPED}。
 * 漏一个就是一个越权漏洞，这里没有"默认安全"可依赖。
 */
@Component
public class InboundHeaderSanitizeFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InboundHeaderSanitizeFilter.class);

    /**
     * 下游可能当作可信身份的请求头，一律剥离后重新注入。
     */
    private static final List<String> STRIPPED = List.of(
            "X-Dev-User-Id",      // 旧版开发态身份头，禁止继续使用
            "X-User-Id",
            "X-Dept-Id",
            "X-Role-Ids",
            "X-Clearance-Level",
            "X-Principal-Keys"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest original = exchange.getRequest();

        List<String> present = STRIPPED.stream()
                .filter(h -> original.getHeaders().containsKey(h))
                .toList();

        if (present.isEmpty()) {
            return chain.filter(exchange);
        }

        // 外部带着身份头进来，要么是前端写错了，要么是有人在探测越权。
        // 两种都值得留痕，但只记头名称，不记值——值本身可能是攻击者构造的注入内容。
        log.warn("剥离客户端自带的身份请求头 headers={} path={} remote={}",
                present, original.getPath().value(), original.getRemoteAddress());

        ServerHttpRequest sanitized = original.mutate()
                .headers(h -> STRIPPED.forEach(h::remove))
                .build();

        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
