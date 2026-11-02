package com.zhishiku.kb.app.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.search.config.SearchExecutionProperties;
import com.zhishiku.kb.app.search.config.SearchLimitsProperties;
import com.zhishiku.kb.app.search.embedding.EmbeddingInstanceReadiness;
import com.zhishiku.kb.app.search.execution.SearchAdmission;
import com.zhishiku.kb.app.search.execution.SearchBudget;
import com.zhishiku.kb.app.search.execution.SearchExecutor;
import com.zhishiku.kb.app.search.execution.SearchMetrics;
import com.zhishiku.kb.app.search.execution.SearchResponseEncoder;
import com.zhishiku.kb.app.search.execution.SearchStageFailure;
import com.zhishiku.kb.app.search.model.ChunkHit;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.rerank.RerankClient;
import com.zhishiku.kb.app.search.service.QueryRetrievalService;
import com.zhishiku.kb.app.search.service.SearchService;
import com.zhishiku.kb.common.embedding.EmbeddingClientRegistry;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.embedding.EmbeddingProfile;
import com.zhishiku.kb.common.embedding.ProfileEmbeddingClient;
import com.zhishiku.kb.common.security.PermissionScope;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.NoopTracerAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchContextPropagationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realFallbackKeepsObservationAndWhitelistedMdcAcrossReusedThreads(boolean enabled) {
        var exporter = InMemorySpanExporter.create();
        runner(enabled).withBean(SpanExporter.class, () -> exporter).run(context -> {
            var tracer = context.getBean(Tracer.class);
            var registry = context.getBean(ObservationRegistry.class);
            var logger = (Logger) LoggerFactory.getLogger(SearchService.class);
            var logs = new ListAppender<ILoggingEvent>() {
                @Override
                protected void append(ILoggingEvent event) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            };
            logs.start();
            logger.addAppender(logs);
            try (var fixture = new Fixture(registry, tracer, context.getBean(SearchContextPropagation.class))) {
                String previousTrace = null;
                for (int request = 0; request < 2; request++) {
                    var observation = Observation.start("test.search.request", registry);
                    String requestId = "request-" + request;
                    String trace;
                    try (var scope = observation.openScope()) {
                        trace = enabled ? tracer.currentSpan().context().traceId() : null;
                        MDC.put("requestId", requestId);
                        MDC.put("private-canary", "must-not-propagate");
                        var service = service(fixture.executor, () -> assertContext(registry, tracer, observation, trace, requestId));
                        assertThat(service.search(7, "secret-query", null, 50, 1)).hasSize(1);
                    } finally {
                        MDC.remove("requestId");
                        MDC.remove("private-canary");
                        observation.stop();
                    }
                    assertThat(logs.list).filteredOn(event -> event.getFormattedMessage().startsWith("搜索降级"))
                            .hasSize(request + 1);
                    var event = logs.list.stream().filter(item -> item.getFormattedMessage().startsWith("搜索降级")).reduce((a, b) -> b).orElseThrow();
                    assertThat(event.getThreadName()).startsWith("verify-search");
                    assertThat(event.getMDCPropertyMap()).containsEntry("requestId", requestId);
                    if (enabled) {
                        assertThat(event.getMDCPropertyMap()).containsEntry("traceId", trace);
                        if (previousTrace != null) {
                            assertThat(trace).isNotEqualTo(previousTrace);
                        }
                        previousTrace = trace;
                    } else {
                        assertThat(event.getMDCPropertyMap()).doesNotContainKeys("traceId", "spanId");
                    }
                    assertThat(event.getFormattedMessage()).doesNotContain("secret-query", "must-not-propagate");
                    assertThat(event.getThrowableProxy()).isNull();
                    fixture.assertCleared();
                }
                fixture.executor.execute(7, execution -> {
                    assertContext(registry, tracer, null, null, null);
                    return fixture.executor.optional(execution, SearchBudget.Stage.EMBEDDING, () -> {
                        assertContext(registry, tracer, null, null, null);
                        return "ok";
                    });
                });
                fixture.assertCleared();
                if (enabled) {
                    context.getBean(SdkTracerProvider.class).forceFlush().join(1, TimeUnit.SECONDS);
                }
                assertThat(exporter.getFinishedSpanItems()).hasSize(enabled ? 2 : 0);
            } finally {
                logger.detachAppender(logs);
                logs.stop();
            }
        });
    }

    @Test
    void standaloneSpanScopesAreClearedOnFailureAndAfterCancelledWorkActuallyExits() {
        runner(true).run(context -> {
            var tracer = context.getBean(Tracer.class);
            var registry = context.getBean(ObservationRegistry.class);
            var root = tracer.nextSpan().name("test.search.manual").start();
            var callers = Executors.newSingleThreadExecutor();
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var execution = new AtomicReference<SearchExecutor.Execution>();
            try (var fixture = new Fixture(registry, tracer, context.getBean(SearchContextPropagation.class))) {
                try (var scope = tracer.withSpan(root)) {
                    MDC.put("requestId", "failed-request");
                    assertThatThrownBy(() -> fixture.executor.execute(7, work -> fixture.executor.optional(work, SearchBudget.Stage.EMBEDDING, () -> {
                        assertContext(registry, tracer, null, root.context().traceId(), "failed-request");
                        throw new IllegalStateException("fixed-failure");
                    }))).isInstanceOf(IllegalStateException.class).hasMessage("fixed-failure");
                } finally {
                    MDC.remove("requestId");
                }
                fixture.assertCleared();
                var caller = callers.submit(() -> {
                    try (var scope = tracer.withSpan(root)) {
                        MDC.put("requestId", "cancelled-request");
                        assertThatThrownBy(() -> fixture.executor.execute(7, work -> {
                            execution.set(work);
                            return fixture.executor.optional(work, SearchBudget.Stage.EMBEDDING, () -> {
                                assertContext(registry, tracer, null, root.context().traceId(), "cancelled-request");
                                started.countDown();
                                awaitIgnoringInterrupt(release);
                                assertContext(registry, tracer, null, root.context().traceId(), "cancelled-request");
                                return "late";
                            });
                        })).isInstanceOf(SearchFailure.class);
                    } finally {
                        MDC.remove("requestId");
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                execution.get().cancel(new SearchFailure(SearchFailure.Kind.CANCELLED));
                caller.get(5, TimeUnit.SECONDS);
                assertThat(execution.get().exited()).isNotDone();
                release.countDown();
                execution.get().exited().get(5, TimeUnit.SECONDS);
                fixture.assertCleared();
            } finally {
                release.countDown();
                callers.shutdownNow();
                root.end();
            }
        });
    }

    @Test
    void springAssemblyInjectsPropagationIntoTheProductionExecutor() {
        runner(true).withUserConfiguration(SearchExecutor.class)
                .withBean(SearchExecutionProperties.class, SearchExecutionProperties::defaults)
                .withBean(SearchAdmission.class, () -> new SearchAdmission(SearchExecutionProperties.defaults()))
                .withBean(SearchMetrics.class, () -> new SearchMetrics(null))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var tracer = context.getBean(Tracer.class);
                    var root = tracer.nextSpan().name("test.search.assembly").start();
                    try (var scope = tracer.withSpan(root)) {
                        MDC.put("requestId", "injected-request");
                        context.getBean(SearchExecutor.class).execute(7, execution -> {
                            assertContext(context.getBean(ObservationRegistry.class), tracer, null, root.context().traceId(), "injected-request");
                            return "ok";
                        });
                    } finally {
                        MDC.remove("requestId");
                        root.end();
                    }
                });
    }

    private static SearchService service(SearchExecutor executor, Runnable check) {
        var permissions = mock(PermissionService.class);
        var control = mock(EmbeddingControlService.class);
        var clients = mock(EmbeddingClientRegistry.class);
        var embedding = mock(ProfileEmbeddingClient.class);
        var retrieval = mock(QueryRetrievalService.class);
        var rerank = mock(RerankClient.class);
        var profile = new EmbeddingProfile("p", "local", "source", "local-hash-v1", "1", 1024,
                "cosine", "identity-v1", "identity-v1", "query", "document", "local-hash-v1");
        var permission = PermissionScope.of(7, (short) 0, List.of(1L), List.of("1:7"));
        var hit = new ChunkHit(1L, 10L, 20L, 1, "title", 1L, (short) 0, 0, "text", null, null, null,
                null, null, null, null, null, 0.5);
        when(permissions.resolve(7, "view")).thenAnswer(call -> {
            check.run();
            return permission;
        });
        when(control.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", profile.fingerprint(), null, null, 1, null));
        when(clients.find("p", profile.fingerprint())).thenReturn(Optional.of(embedding));
        when(embedding.profile()).thenReturn(profile);
        when(embedding.embed(any(), any())).thenAnswer(call -> {
            check.run();
            throw new SearchStageFailure(SearchStageFailure.Reason.UPSTREAM);
        });
        when(retrieval.retrieve(any(), any(), any(), anyInt())).thenReturn(List.of(hit));
        when(rerank.rerank(any(), any(), anyInt())).thenAnswer(call -> {
            check.run();
            return List.of(hit);
        });
        return new SearchService(permissions, clients, control, retrieval, rerank, mock(EmbeddingInstanceReadiness.class), executor,
                new SearchResponseEncoder(new ObjectMapper(), SearchLimitsProperties.defaults()), SearchLimitsProperties.defaults());
    }

    private static void assertContext(ObservationRegistry registry, Tracer tracer, Observation observation, String trace, String requestId) {
        assertThat(registry.getCurrentObservation()).isSameAs(observation);
        assertThat(MDC.get("requestId")).isEqualTo(requestId);
        assertThat(MDC.get("private-canary")).isNull();
        if (trace == null) {
            assertThat(tracer.currentSpan()).isIn(null, Span.NOOP);
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
        } else {
            assertThat(tracer.currentSpan()).isNotNull();
            assertThat(tracer.currentSpan().context().traceId()).isEqualTo(trace);
            assertThat(MDC.get("traceId")).isEqualTo(trace);
            assertThat(MDC.get("spanId")).isEqualTo(tracer.currentSpan().context().spanId());
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) {
            try {
                assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException ignored) {
                // 模拟底层工作在取消后仍执行，直到真实退出才释放上下文与容量。
            }
        }
    }

    private static ApplicationContextRunner runner(boolean enabled) {
        return new ApplicationContextRunner().withUserConfiguration(TelemetryOnly.class, SearchContextPropagation.class)
                .withPropertyValues("kb.telemetry.tracing.enabled=" + enabled, "kb.telemetry.tracing.otlp-endpoint=https://collector.invalid/v1/traces")
                .withInitializer(context -> new TelemetryEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), null));
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservationAutoConfiguration.class,
            org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration.class,
            OpenTelemetryAutoConfiguration.class, NoopTracerAutoConfiguration.class, MicrometerTracingAutoConfiguration.class})
    static class TelemetryOnly {
    }

    private static final class Fixture implements AutoCloseable {
        private final ExecutorService workers = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "verify-search"));
        private final ExecutorService external = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "verify-external"));
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ObservationRegistry registry;
        private final Tracer tracer;
        private final SearchExecutor executor;

        private Fixture(ObservationRegistry registry, Tracer tracer, SearchContextPropagation propagation) {
            this.registry = registry;
            this.tracer = tracer;
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            var properties = SearchExecutionProperties.defaults();
            executor = new SearchExecutor(properties, new SearchAdmission(properties), System::nanoTime,
                    workers, external, Executors.newSingleThreadScheduledExecutor(), Executors.newSingleThreadExecutor());
            executor.setContextPropagation(propagation);
        }

        private void assertCleared() throws Exception {
            workers.submit(() -> assertContext(registry, tracer, null, null, null)).get(5, TimeUnit.SECONDS);
            external.submit(() -> assertContext(registry, tracer, null, null, null)).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            executor.close();
            meters.close();
        }
    }
}
