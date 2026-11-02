package com.zhishiku.kb.worker.telemetry;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HttpMetricsConfiguration {
    @Bean
    public MeterFilter managementEndpointMetricsFilter(@Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                String uri = id.getTag("uri");
                return id.getName().startsWith("http.server.requests") && uri != null
                        && (uri.equals(basePath) || uri.startsWith(basePath + "/") || uri.equals("MANAGEMENT"))
                        ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }

    @Bean
    public MeterFilter httpUriLimit(@Value("${management.metrics.web.server.max-uri-tags:100}") int maximum) {
        if (maximum <= 0) {
            throw new IllegalStateException("management.metrics.web.server.max-uri-tags");
        }
        return MeterFilter.maximumAllowableTags("http.server.requests", "uri", maximum, MeterFilter.deny());
    }
}
