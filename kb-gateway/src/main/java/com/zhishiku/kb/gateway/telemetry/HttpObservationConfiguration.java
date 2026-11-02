package com.zhishiku.kb.gateway.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.cloud.gateway.filter.headers.observation.DefaultGatewayObservationConvention;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class HttpObservationConfiguration {
    @Bean
    public ServerConvention serverRequestObservationConvention(@Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        return new ServerConvention(basePath);
    }

    @Bean
    public ClientConvention gatewayObservationConvention() {
        return new ClientConvention();
    }

    public static final class ServerConvention extends DefaultServerRequestObservationConvention {
        private final String managementPath;

        public ServerConvention() {
            this("/actuator");
        }

        public ServerConvention(String managementPath) {
            this.managementPath = managementPath;
        }
        @Override
        protected KeyValue uri(ServerRequestObservationContext context) {
            return KeyValue.of("uri", route(context));
        }

        @Override
        public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
            return KeyValues.of("http.route", route(context));
        }

        @Override
        public String getContextualName(ServerRequestObservationContext context) {
            return "http " + route(context);
        }

        private String route(ServerRequestObservationContext context) {
            String path = context.getCarrier().getPath().pathWithinApplication().value();
            // 路由模板在开始观测时尚不可用；原始路径只用于识别管理类别。
            if (path.equals(managementPath) || path.startsWith(managementPath + "/")) {
                return "MANAGEMENT";
            }
            String pattern = context.getPathPattern();
            if (pattern != null && (pattern.equals(managementPath) || pattern.startsWith(managementPath + "/"))) {
                return "MANAGEMENT";
            }
            Object route = context.getAttributes().get(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            return route instanceof Route matched ? matched.getId() : "UNKNOWN";
        }
    }

    public static final class ClientConvention extends DefaultGatewayObservationConvention {
        @Override
        public KeyValues getLowCardinalityKeyValues(GatewayContext context) {
            Route route = context.getServerWebExchange().getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String target = route != null && "kb-ai-stream".equals(route.getId()) ? "internal-ai" : "internal-app";
            int status = context.getResponse() == null || context.getResponse().getStatusCode() == null ? 0
                    : context.getResponse().getStatusCode().value();
            String outcome = status == 0 ? "UNKNOWN" : status >= 500 ? "SERVER_ERROR" : status >= 400 ? "CLIENT_ERROR" : "SUCCESS";
            return KeyValues.of("client.name", target, "method", context.getRequest().getMethod().name(),
                    "status", status == 0 ? "UNKNOWN" : Integer.toString(status), "outcome", outcome);
        }

        @Override
        public KeyValues getHighCardinalityKeyValues(GatewayContext context) {
            return KeyValues.empty();
        }

        @Override
        public String getContextualName(GatewayContext context) {
            return "http internal";
        }
    }
}
