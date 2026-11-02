package com.zhishiku.kb.ai.telemetry;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LoggingSystemProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TelemetryLoggingTest {
    @Test
    void bootConsoleIncludesActiveTraceAndHandlesDisabledTracing(CapturedOutput output) {
        for (boolean enabled : new boolean[]{true, false}) {
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                            org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
                            OpenTelemetryAutoConfiguration.class))
                    .withPropertyValues("kb.telemetry.tracing.enabled=" + enabled,
                            "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                    .run(context -> {
                        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                        new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null);
                        var constructor = Class.forName("org.springframework.boot.actuate.autoconfigure.tracing.LogCorrelationEnvironmentPostProcessor")
                                .getDeclaredConstructor();
                        constructor.setAccessible(true);
                        ((EnvironmentPostProcessor) constructor.newInstance()).postProcessEnvironment(context.getEnvironment(), new SpringApplication());
                        var logging = LoggingSystem.get(getClass().getClassLoader());
                        try {
                            new LoggingSystemProperties(context.getEnvironment()).apply();
                            logging.initialize(new LoggingInitializationContext(context.getEnvironment()), null, null);
                            if (enabled) {
                                var tracer = context.getBean(Tracer.class);
                                var span = tracer.nextSpan().name("test.logging").start();
                                try (var scope = tracer.withSpan(span)) {
                                    MDC.put("requestId", "logging-request");
                                    LoggerFactory.getLogger(TelemetryLoggingTest.class).info("telemetry-enabled-marker");
                                } finally {
                                    MDC.remove("requestId");
                                    span.end();
                                }
                                String line = output.getOut().lines().filter(value -> value.contains("telemetry-enabled-marker")).reduce((first, last) -> last).orElseThrow();
                                assertThat(line).contains(span.context().traceId(), span.context().spanId(), "logging-request");
                            } else {
                                assertThat(MDC.get("traceId")).isNull();
                                assertThat(MDC.get("spanId")).isNull();
                                LoggerFactory.getLogger(TelemetryLoggingTest.class).info("telemetry-disabled-marker");
                                assertThat(output.getOut()).contains("telemetry-disabled-marker");
                            }
                        } finally {
                            logging.cleanUp();
                        }
                    });
        }
    }
}
