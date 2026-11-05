package com.zhishiku.notifier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

@SpringBootConfiguration
@EnableAutoConfiguration
public class NotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }

    @Bean
    NotifierSettings notifierSettings(ConfigurableEnvironment environment) {
        return NotifierBootstrap.prepare(environment);
    }

    @Bean
    Admission admission() {
        return new Admission(System::nanoTime);
    }

    @Bean
    NotifierMetrics notifierMetrics(MeterRegistry registry, Admission admission) {
        return new NotifierMetrics(registry, admission);
    }

    @Bean
    DeliveryService deliveryService(NotifierSettings settings, Admission admission, NotifierMetrics metrics) {
        return new DeliveryService(settings, new JdkTransport(), admission, metrics, Clock.systemUTC(), System::nanoTime);
    }

    @Bean
    FilterRegistrationBean<IngressFilter> notifierIngress(NotifierSettings settings, DeliveryService service, NotifierMetrics metrics) {
        return registration(settings, service, metrics);
    }

    static FilterRegistrationBean<IngressFilter> registration(NotifierSettings settings, DeliveryService service, NotifierMetrics metrics) {
        FilterRegistrationBean<IngressFilter> bean = new FilterRegistrationBean<>(new IngressFilter(settings, service, metrics));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        bean.setAsyncSupported(false);
        return bean;
    }

    @Bean
    HealthIndicator notifierLocalHealth(NotifierSettings settings) {
        // 配置已在装配前验证；不探测业务依赖或机器人。
        return () -> Health.up().build();
    }

    @Bean
    MeterFilter notifierHttpMeterBoundary() {
        return MeterFilter.deny(id -> id.getName().startsWith("http."));
    }

    @Bean
    ObservationPredicate notifierHttpObservationBoundary() {
        return (name, context) -> !name.startsWith("http.");
    }
}
