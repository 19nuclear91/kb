package com.zhishiku.notifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.observation.Observation;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PrivacyMetricsTest extends TestSupport {
    @Test
    void logsAndRegistryNeverContainBodiesCredentialsOrUnboundedIdentity() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        try {
            behavior = (uri, body, deadline) -> {
                nanos.addAndGet(Duration.ofMillis(75).toNanos());
                throw new IOException("provider-errmsg-canary " + uri + " " + BEARER + " " + SECRET);
            };
            mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
            behavior = (uri, body, deadline) -> new Transport.Response(200,
                    BoundedJson.encode(java.util.Map.of("errcode", 1, "errmsg", "provider-errmsg-canary")));
            mvc.perform(post(webhook(1))).andExpect(status().isServiceUnavailable());
            String exported = registry.scrape();
            String logged = captured.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
            assertThat(logged).contains("notification_delivery outcome=network", "notification_delivery outcome=remote_business");
            assertThat(captured.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            for (String canary : Set.of(TOKEN, SECRET, BEARER, "question-password-body-canary", "sensitive-common-canary",
                    "untrusted-group-canary", "private-fingerprint-canary", "provider-errmsg-canary", "oapi.dingtalk.com", "access_token=", "sign=")) {
                assertThat(logged).doesNotContain(canary);
                assertThat(exported).doesNotContain(canary);
            }
            assertThat(exported).contains("kb_alert_notifier_requests_total{outcome=\"failure\"} 2.0",
                    "kb_alert_notifier_deliveries_total{outcome=\"network\"} 1.0",
                    "kb_alert_notifier_delivery_duration_seconds_count{outcome=\"network\"} 1",
                    "kb_alert_notifier_inflight 0.0");
            for (Meter meter : registry.getMeters()) {
                assertThat(meter.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "reason"));
            }
        } finally {
            root.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void genericHttpMetricsAndObservationsAreDeniedRatherThanCarryingRawUris() {
        registry.counter("http.server.requests", "uri", "/user-private-canary").increment();
        registry.counter("http.client.requests", "uri", "token-canary").increment();
        assertThat(registry.scrape()).doesNotContain("user-private-canary", "token-canary", "http_server_requests");
        assertThat(new NotifierApplication().notifierHttpObservationBoundary().test("http.server.requests", new Observation.Context())).isFalse();
        assertThat(new NotifierApplication().notifierHttpObservationBoundary().test("http.client.requests", new Observation.Context())).isFalse();
    }

    @Test
    void failedSecretReadHasNoPathValueOrNestedException() throws Exception {
        Files.writeString(directory.resolve("token"), "provider-credential-canary with whitespace");
        assertThatThrownBy(() -> new NotifierSettings(properties()))
                .hasMessage("Invalid configuration: kb.notifier.dingtalk-token-file").hasNoCause();
        Files.writeString(directory.resolve("token"), TOKEN);
        Files.writeString(directory.resolve("signing"), "secret");
        assertThatThrownBy(() -> new NotifierSettings(properties()))
                .hasMessage("Invalid configuration: kb.notifier.dingtalk-secret-file").hasNoCause();
    }
}
