package com.zhishiku.kb.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 网关。
 *
 * <p>职责限定在四件事：路由转发、Sa-Token 会话校验、入站请求头净化、流式响应放行。
 * 不做业务权限决策——细粒度鉴权仍在 {@code kb-app} 的 {@code PermissionService}。
 *
 * <p>本模块是《架构决策记录.md》2.5 部署计数口径里"三类应用角色之外另计的基础设施"，
 * 换成 Nginx / APISIX / 云厂商网关都可以，但下面两条约束必须由接手者实现：
 * 会话令牌必须由服务端校验；SSE 路由不得缓冲、不得套用业务短超时。
 */
@SpringBootApplication
public class KbGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbGatewayApplication.class, args);
    }
}
