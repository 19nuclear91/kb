package com.zhishiku.kb.ai.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.web.reactive.function.client.ClientRequestObservationContext;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

@Configuration(proxyBeanMethods = false)
public class HttpObservationConfiguration {
    @Bean
    public ServerConvention serverRequestObservationConvention(@Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        return new ServerConvention(basePath);
    }

    @Bean
    public ClientConvention clientRequestObservationConvention() {
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
            String path = context.getCarrier().getPath().pathWithinApplication().value();
            // 在路由匹配前只投影管理类别，不将原始路径写入标签。
            if (path.equals(managementPath) || path.startsWith(managementPath + "/")) {
                return KeyValue.of("uri", "MANAGEMENT");
            }
            return super.uri(context);
        }

        @Override
        public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
            return KeyValues.of("http.route", context.getPathPattern() == null ? "UNKNOWN" : context.getPathPattern());
        }
    }

    public static final class ClientConvention extends DefaultClientRequestObservationConvention {
        @Override
        public KeyValues getLowCardinalityKeyValues(ClientRequestObservationContext context) {
            return KeyValues.of(method(context), status(context), outcome(context), KeyValue.of("client.name", "internal-app"));
        }

        @Override
        public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
            return KeyValues.empty();
        }

        @Override
        public String getContextualName(ClientRequestObservationContext context) {
            return "http internal-app";
        }
    }
}
