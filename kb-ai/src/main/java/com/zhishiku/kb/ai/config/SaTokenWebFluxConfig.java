package com.zhishiku.kb.ai.config;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.reactor.context.SaReactorSyncHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.common.api.R;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * AI 服务的 WebFlux 会话校验。细粒度文档权限由 kb-app 负责。
 */
@Configuration
public class SaTokenWebFluxConfig {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                .addInclude("/api/**")
                .setAuth(exchange -> StpUtil.checkLogin())
                .setError(error -> {
                    var exchange = SaReactorSyncHolder.getExchange();
                    if (exchange != null) {
                        // 未认证是 401（会话缺失或失效），不是 500：500 会把一次
                        // 可预期的拒绝报成服务端故障。403 留给已认证但无资格，
                        // 该语义在网关、kb-app 与 kb-ai 间保持一致（设计 D11）。
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    }
                    return SaManager.getSaJsonTemplate()
                            .objectToJson(R.fail("登录状态无效或已过期"));
                });
    }
}
