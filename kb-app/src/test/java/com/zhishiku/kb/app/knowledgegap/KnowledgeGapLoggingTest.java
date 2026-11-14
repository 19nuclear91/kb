package com.zhishiku.kb.app.knowledgegap;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zhishiku.kb.app.config.GlobalExceptionHandler;
import com.zhishiku.kb.app.knowledgegap.web.KnowledgeGapAdvice;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationService;
import com.zhishiku.kb.app.notification.service.NotificationDerivationScheduler;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.telemetry.TelemetrySpanFilter;
import io.micrometer.tracing.otel.bridge.CompositeSpanExporter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 实际日志级别、固定异常投影与内存 span exporter；不初始化应用或网络导出器。 */
class KnowledgeGapLoggingTest {
    private static final String CANARY = "gap-description solution-body candidate-name satoken-secret";

    @Test
    void actualYamlDisablesMapperSqlAndParameterMessages() throws Exception {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        String prefix = "com.zhishiku.kb.app.knowledgegap.mapper";
        Level configured = Level.valueOf(environment.getRequiredProperty("logging.level." + prefix));
        assertThat(configured).isEqualTo(Level.OFF);
        Logger parent = (Logger) LoggerFactory.getLogger(prefix);
        Logger child = (Logger) LoggerFactory.getLogger(prefix + ".KnowledgeGapMapper.insertEntry");
        Level oldParent = parent.getLevel();
        Level oldChild = child.getLevel();
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        child.addAppender(output);
        try {
            parent.setLevel(configured);
            child.setLevel(null);
            child.debug("Preparing: {}", CANARY);
            child.trace("Parameters: {}", CANARY);
            assertThat(output.list).isEmpty();
        } finally {
            parent.setLevel(oldParent);
            child.setLevel(oldChild);
            child.detachAppender(output);
            output.stop();
        }
    }

    @Test
    void rawExceptionsNeverReachGapResponseOrSchedulerLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var output = new ListAppender<ILoggingEvent>();
        output.start();
        root.addAppender(output);
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                    new MockHttpServletRequest("POST", "/api/knowledge-gaps")));
            var response = new GlobalExceptionHandler().handleOther(new IllegalStateException(CANARY));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().message()).doesNotContain(CANARY);
            for (int status : List.of(400, 401, 403, 404, 409, 413, 503)) {
                assertThat(KnowledgeGapAdvice.project(new QueryException(status, CANARY)).getBody().message())
                        .doesNotContain("gap-description", "solution-body", "candidate-name", "satoken-secret");
            }
            var oldSource = mock(NotificationDerivationService.class);
            var gapSource = mock(GapNotificationDerivationService.class);
            doThrow(new IllegalStateException(CANARY)).when(oldSource).runOnce();
            doThrow(new IllegalStateException(CANARY)).when(gapSource).runOnce();
            new NotificationDerivationScheduler(oldSource, gapSource).tick();
            assertThat(output.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("gap-description", "solution-body", "candidate-name", "satoken-secret");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            RequestContextHolder.resetRequestAttributes();
            root.detachAppender(output);
            output.stop();
        }
    }

    @Test
    void failedGapSpanKeepsOnlySafeRouteAndExceptionType() {
        var output = InMemorySpanExporter.create();
        var exporter = new CompositeSpanExporter(List.of(output), List.of(), List.of(), List.of(new TelemetrySpanFilter()));
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var span = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("test")
                    .spanBuilder("http.server.requests").startSpan();
            span.setAttribute("http.route", "/api/knowledge-gaps/{gapId}/solutions");
            span.setAttribute("url.full", "/api/knowledge-gaps?nameQuery=" + CANARY);
            span.setAttribute("http.request.header.satoken", CANARY);
            span.recordException(new IllegalStateException(CANARY));
            span.setStatus(StatusCode.ERROR, CANARY);
            span.end();
            assertThat(output.getFinishedSpanItems()).singleElement().satisfies(data -> {
                assertThat(data.getAttributes().size()).isEqualTo(1);
                assertThat(data.getAttributes().get(AttributeKey.stringKey("http.route")))
                        .isEqualTo("/api/knowledge-gaps/{gapId}/solutions");
                assertThat(data.getAttributes().toString()).doesNotContain(CANARY);
                assertThat(data.getStatus().getDescription()).isEmpty();
                assertThat(data.getEvents()).singleElement().satisfies(event -> {
                    assertThat(event.getAttributes().size()).isEqualTo(1);
                    assertThat(event.getAttributes().get(AttributeKey.stringKey("exception.type")))
                            .isEqualTo("java.lang.IllegalStateException");
                });
            });
        }
    }
}
