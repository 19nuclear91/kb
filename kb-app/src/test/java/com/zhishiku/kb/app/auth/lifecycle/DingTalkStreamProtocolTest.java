package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DingTalkStreamProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DingTalkLifecycleSettings settings = DingTalkLifecycleSettingsTest.configured(true);

    @Test
    void rejectedInputsRecordOnlyFixedCategoriesAndNeverReachPersistence() throws Exception {
        var registry = new SimpleMeterRegistry();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> {
            throw new AssertionError("拒绝的输入不应进入持久化");
        });
        protocol.setObservations(new LifecycleObservations(registry));
        var logger = (Logger) LoggerFactory.getLogger(LifecycleObservations.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            protocol.handle(frame("private-corp", "private-app", "user_leave_org", "private-delivery", List.of("private-employee")));
            protocol.handle("broken-private-payload");
            protocol.handle("x".repeat(1048577));
            protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery", java.util.Collections.nCopies(1001, "private-employee")));
            protocol.handle(frame("ding-corp", "unified-application", "unknown", "delivery", List.of("private-employee")));
            protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery", List.of(" ")));
            for (String reason : List.of("SCOPE_MISMATCH", "MALFORMED_MESSAGE", "MESSAGE_LIMIT", "EMPLOYEE_LIMIT", "UNSUPPORTED_EVENT", "INVALID_EVENT")) {
                assertThat(registry.get("kb.lifecycle.input_rejections").tag("reason", reason).counter().count()).isEqualTo(1);
            }
            assertThat(registry.get("kb.lifecycle.reception").tag("outcome", "COMMITTED").counter().count()).isZero();
            assertThat(captured.list).hasSize(6).allSatisfy(entry -> {
                assertThat(entry.getFormattedMessage()).startsWith("Lifecycle input rejected category=").doesNotContain("private");
                assertThat(entry.getThrowableProxy()).isNull();
            });
            assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                    .allSatisfy(tag -> assertThat(tag.getValue()).doesNotContain("private")));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void systemPingWithoutVersionEchoesOnlyItsPayloadAndDisconnectNeedsNoAck() throws Exception {
        var consumed = new AtomicBoolean();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> consumed.set(true));
        var ping = json.writeValueAsString(Map.of("type", "SYSTEM", "headers", Map.of("topic", "ping", "messageId", "ping-id",
                "contentType", "application/json"), "data", "{\"opaque\":\"nonce\"}"));
        var reply = protocol.handle(ping);
        assertThat(reply.disconnect()).isFalse();
        assertThat(json.readTree(json.readTree(reply.text()).path("data").asText()).path("opaque").asText()).isEqualTo("nonce");
        var disconnect = protocol.handle("{\"type\":\"SYSTEM\",\"headers\":{\"topic\":\"disconnect\"},\"data\":\"{}\"}");
        assertThat(disconnect.disconnect()).isTrue();
        assertThat(disconnect.text()).isNull();
        assertThat(consumed).isFalse();
    }

    @Test
    void sdkAckFollowsConsumerReturnAndUsesBusinessEventIdNotMessageId() throws Exception {
        var committed = new AtomicBoolean();
        var received = new AtomicReference<LifecycleIncomingEvent>();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> {
            received.set(event);
            committed.set(true);
        });
        var reply = protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery-1", List.of("b", "a", "a")));
        assertThat(committed).isTrue();
        assertThat(ack(reply)).isEqualTo("SUCCESS");
        assertThat(received.get().eventId()).isEqualTo("business-event");
        assertThat(received.get().employees()).containsExactly("a", "b");
        assertThat(json.readTree(reply.text()).path("headers").path("messageId").asText()).isEqualTo("delivery-1");
        var first = received.get();
        protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery-2", List.of("a", "b")));
        assertThat(received.get().digest()).isEqualTo(first.digest());
        assertThat(first.toString()).doesNotContain("business-event", "ding-corp");
    }

    @Test
    void transactionFailureProducesLaterAndNeverLeaksException() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger("com.dingtalk.open.app.api.protocol.EventCommandExecutor");
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> {
            throw new IllegalStateException("secret employee raw-event");
        });
        try {
            var reply = protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery", List.of("a")));
            assertThat(ack(reply)).isEqualTo("LATER");
            assertThat(reply.text()).doesNotContain("secret", "employee", "raw-event");
            assertThat(captured.list).isEmpty();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void differentCorpAndUnifiedApplicationAreRejectedEvenWhenOAuthClientMatches() throws Exception {
        var consumed = new AtomicBoolean();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> consumed.set(true));
        assertThat(ack(protocol.handle(frame("other", "unified-application", "user_leave_org", "delivery", List.of("a")))))
                .isEqualTo("LATER");
        assertThat(ack(protocol.handle(frame("ding-corp", "oauth-client", "user_leave_org", "delivery", List.of("a")))))
                .isEqualTo("LATER");
        assertThat(consumed).isFalse();
    }

    @Test
    void malformedOversizedAndUnknownEventsDoNotReachReceiver() throws Exception {
        var consumed = new AtomicBoolean();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), event -> consumed.set(true));
        assertThat(protocol.handle("broken").disconnect()).isTrue();
        assertThat(protocol.handle("x".repeat(1048577)).disconnect()).isTrue();
        assertThat(ack(protocol.handle(frame("ding-corp", "unified-application", "user_leave_org", "delivery", java.util.Collections.nCopies(1001, "a")))))
                .isEqualTo("LATER");
        assertThat(ack(protocol.handle(frame("ding-corp", "unified-application", "unknown", "delivery", List.of("a")))))
                .isEqualTo("LATER");
        assertThat(consumed).isFalse();
    }

    @Test
    void joinEventOnlyPassesAnExplicitIgnoredRecord() throws Exception {
        var received = new AtomicReference<LifecycleIncomingEvent>();
        var protocol = new DingTalkStreamProtocol(settings, new DingTalkEventParser(settings), received::set);
        assertThat(ack(protocol.handle(frame("ding-corp", "unified-application", "user_add_org", "delivery", List.of("a")))))
                .isEqualTo("SUCCESS");
        assertThat(received.get().offboarding()).isFalse();
        assertThat(received.get().employees()).isEmpty();
    }

    private String frame(String corp, String app, String type, String message, List<String> employees) throws Exception {
        return json.writeValueAsString(Map.of("specVersion", "1.0", "type", "EVENT", "headers", Map.of(
                "eventCorpId", corp, "eventUnifiedAppId", app, "eventType", type, "eventId", "business-event",
                "eventBornTime", "1767225600000", "messageId", message, "contentType", "application/json", "topic", "dingTalk"),
                "data", json.writeValueAsString(Map.of("userId", employees))));
    }

    private String ack(DingTalkStreamProtocol.Reply reply) throws Exception {
        assertThat(reply.disconnect()).isFalse();
        return json.readTree(json.readTree(reply.text()).path("data").asText()).path("status").asText();
    }
}
