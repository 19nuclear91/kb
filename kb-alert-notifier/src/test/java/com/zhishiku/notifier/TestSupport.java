package com.zhishiku.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

abstract class TestSupport {
    static final String BEARER = "BearerCanaryA9d23L5m74q10vK6x98wF12z";
    static final String TOKEN = "RobotTokenCanaryA1234567890";
    static final String SECRET = "SECSigningCanaryA1234567890";
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir(factory = ScratchFactory.class)
    Path directory;
    NotifierSettings settings;
    PrometheusMeterRegistry registry;
    AtomicLong nanos;
    AtomicInteger calls;
    Admission admission;
    NotifierMetrics metrics;
    DeliveryService service;
    IngressFilter filter;
    MockMvc mvc;
    Transport behavior;
    URI lastUri;
    byte[] lastPayload;

    public static class ScratchFactory implements TempDirFactory {
        @Override
        public Path createTempDirectory(AnnotatedElementContext element, ExtensionContext context) throws Exception {
            String scratch = System.getenv("PI_SCRATCH_DIR");
            Path parent = Path.of(scratch == null ? System.getProperty("java.io.tmpdir") : scratch);
            return Files.createTempDirectory(parent, "notifier-test-");
        }
    }

    @BeforeEach
    void setup() throws Exception {
        Files.writeString(directory.resolve("bearer"), BEARER + "\n");
        Files.writeString(directory.resolve("token"), TOKEN);
        Files.writeString(directory.resolve("signing"), SECRET);
        Files.write(directory.resolve("catalog.json"), BoundedJson.encode(catalog()));
        settings = new NotifierSettings(properties());
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(new NotifierApplication().notifierHttpMeterBoundary());
        nanos = new AtomicLong();
        calls = new AtomicInteger();
        admission = new Admission(nanos::get);
        metrics = new NotifierMetrics(registry, admission);
        behavior = (uri, body, deadline) -> success();
        service = new DeliveryService(settings, (uri, body, deadline) -> {
            calls.incrementAndGet();
            lastUri = uri;
            lastPayload = body;
            return behavior.exchange(uri, body, deadline);
        }, admission, metrics, Clock.fixed(Instant.ofEpochMilli(1700000000000L), ZoneOffset.UTC), nanos::get);
        filter = new IngressFilter(settings, service, metrics);
        mvc = MockMvcBuilders.standaloneSetup(new LocalEndpoints(new NotifierApplication().notifierLocalHealth(settings), registry))
                .addFilters(filter).build();
    }

    NotifierProperties properties() {
        return new NotifierProperties("prod", "kb-main", directory.resolve("catalog.json").toString(),
                directory.resolve("bearer").toString(), directory.resolve("token").toString(),
                directory.resolve("signing").toString(), "https://grafana.corp.internal", "https://admin.corp.internal",
                null, null, null, null);
    }

    MockEnvironment environment() {
        return new MockEnvironment().withProperty("kb.notifier.environment", "prod")
                .withProperty("kb.notifier.deployment", "kb-main")
                .withProperty("kb.notifier.catalog-file", directory.resolve("catalog.json").toString())
                .withProperty("kb.notifier.bearer-secret-file", directory.resolve("bearer").toString())
                .withProperty("kb.notifier.dingtalk-token-file", directory.resolve("token").toString())
                .withProperty("kb.notifier.dingtalk-secret-file", directory.resolve("signing").toString())
                .withProperty("kb.notifier.grafana-root", "https://grafana.corp.internal")
                .withProperty("kb.notifier.admin-root", "https://admin.corp.internal");
    }

    static ArrayNode catalog() {
        ArrayNode array = JSON.createArrayNode();
        for (String name : AlertCatalog.NAMES.stream().sorted().toList()) {
            ObjectNode item = array.addObject();
            item.put("name", name);
            item.putArray("services").add("worker").add("app");
            item.put("severity", "warning").put("family", "parse").put("summary", "固定中文摘要：请核对采样及状态😀");
            item.putObject("link").put("root", "grafana").put("path", "/d/kb-parse-index");
        }
        return array;
    }

    static ObjectNode webhook(int size) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", "4").put("receiver", "dingtalk").put("groupKey", "untrusted-group-canary")
                .put("status", "firing").put("truncatedAlerts", 0).put("externalURL", "https://evil.invalid/private");
        root.set("groupLabels", labels());
        root.set("commonLabels", labels());
        root.putObject("commonAnnotations").put("summary", "sensitive-common-canary");
        ArrayNode alerts = root.putArray("alerts");
        for (int index = 0; index < size; index++) {
            ObjectNode alert = alerts.addObject();
            alert.put("status", "firing").put("startsAt", "2025-01-01T00:00:00Z")
                    .put("endsAt", "0001-01-01T00:00:00Z").put("generatorURL", "https://evil.invalid/token")
                    .put("fingerprint", "private-fingerprint-canary");
            alert.set("labels", labels());
            alert.putObject("annotations").put("summary", "question-password-body-canary");
        }
        return root;
    }

    static ObjectNode labels() {
        return JSON.createObjectNode().put("environment", "prod").put("deployment", "kb-main")
                .put("service", "worker").put("alertname", "KbParseDeadLetters").put("severity", "warning");
    }

    static ObjectNode alert(ObjectNode root, int index) {
        return (ObjectNode) root.get("alerts").get(index);
    }

    static Transport.Response success() {
        return new Transport.Response(200, BoundedJson.encode(Map.of("errcode", 0, "errmsg", "ok")));
    }

    MockHttpServletRequestBuilder post(byte[] body) {
        return MockMvcRequestBuilders.post(IngressFilter.PATH).with(request -> {
            request.setLocalPort(8090);
            return request;
        }).header("Authorization", "Bearer " + BEARER).contentType("application/json").content(body);
    }

    MockHttpServletRequestBuilder post(ObjectNode body) {
        return post(BoundedJson.encode(body));
    }

    @RestController
    static class LocalEndpoints {
        private final HealthIndicator health;
        private final PrometheusMeterRegistry registry;

        LocalEndpoints(HealthIndicator health, PrometheusMeterRegistry registry) {
            this.health = health;
            this.registry = registry;
        }

        @GetMapping("/actuator/health")
        Health health() {
            return health.health();
        }

        @GetMapping("/actuator/prometheus")
        String prometheus() {
            return registry.scrape();
        }
    }
}
