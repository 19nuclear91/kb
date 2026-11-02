package com.zhishiku.kb.gateway.telemetry;

import com.zhishiku.kb.common.config.ProductionSettings;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Configuration(proxyBeanMethods = false)
public class HistogramConfiguration {
    @Bean
    public MeterFilter telemetryHistograms(Environment environment) {
        Map<String, double[]> boundaries = buckets(environment);
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                double[] buckets = boundaries.get(id.getName());
                return buckets == null ? config : DistributionStatisticConfig.builder().serviceLevelObjectives(buckets).build().merge(config);
            }
        };
    }

    static Map<String, double[]> buckets(Environment environment) {
        var search = values(environment, "search", "1.5s,2s,3s,8s");
        var parse = new TreeSet<Double>();
        Arrays.stream(values(environment, "parse", "1s,5s,30s,60s,300s")).forEach(parse::add);
        try {
            long seconds = environment.getProperty("kb.worker.task-timeout-seconds", Long.class, 900L);
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1_000_000_000L) {
                throw new IllegalStateException();
            }
            parse.add(seconds * 1_000_000_000.0);
        } catch (RuntimeException error) {
            throw new IllegalStateException("kb.worker.task-timeout-seconds");
        }
        return Map.of("kb.search.stage.duration", search, "kb.search.options.stage.duration", search,
                "kb.model.call.duration", values(environment, "model", "1s,5s,15s,30s,60s"),
                "kb.parse.task.duration", parse.stream().mapToDouble(Double::doubleValue).toArray(),
                "http.server.requests", values(environment, "http", "100ms,500ms,2s,10s"));
    }

    private static double[] values(Environment environment, String name, String defaults) {
        String key = "kb.telemetry.histograms." + name;
        try {
            List<String> values = Binder.get(environment).bind(key, Bindable.listOf(String.class)).orElse(List.of(defaults));
            return Arrays.stream(ProductionSettings.histogramSeconds(key, String.join(",", values)))
                    .map(seconds -> seconds * 1_000_000_000.0).toArray();
        } catch (RuntimeException error) {
            throw new IllegalStateException(key);
        }
    }
}
