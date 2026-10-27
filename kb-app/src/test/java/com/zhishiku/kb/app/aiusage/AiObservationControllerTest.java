package com.zhishiku.kb.app.aiusage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.service.AiUsageWriter;
import com.zhishiku.kb.app.aiusage.service.AiObservationService;
import com.zhishiku.kb.app.aiusage.web.AiObservationController;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.auth.service.ConversationSessionService;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 直接调用真实控制器；主体、写服务和 nonce 均为进程内替身。
 */
class AiObservationControllerTest {
    private final AiObservationProperties properties = new AiObservationProperties(new MockEnvironment());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final AiObservationService service = mock(AiObservationService.class);
    private final HashSet<String> seen = new HashSet<>();
    private final AiObservationController controller;
    private final UUID id = UUID.randomUUID();

    AiObservationControllerTest() {
        properties.setSecret("0123456789abcdef0123456789abcdef");
        controller = new AiObservationController(properties, service, json,
                (caller, nonce, ttl) -> seen.add(nonce), clock);
    }

    private MockHttpServletRequest signed(String action, String body, String token, Clock signClock) {
        var request = new MockHttpServletRequest("POST", "/internal/ai-observability/" + action);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        request.setContent(bytes);
        if (token != null) request.addHeader("satoken", token);
        var signature = RequestSignature.sign(properties.getSecret(), "kb-ai", "POST", request.getRequestURI(), bytes, token, signClock);
        request.addHeader("X-Service-Caller", signature.caller());
        request.addHeader("X-Service-Timestamp", signature.timestamp());
        request.addHeader("X-Service-Nonce", signature.nonce());
        request.addHeader("X-Service-Signature", signature.signature());
        return request;
    }

    private String registration() throws Exception {
        return json.writeValueAsString(new AiObservation.Registration(1, id, clock.instant(), "SINGLE", null));
    }

    @Test
    void disabledWithoutServiceSecretCannotAuthenticateFixedEvents() {
        properties.setSecret("");
        status(() -> controller.status(new MockHttpServletRequest("POST", "/internal/ai-observability/status")), 503);
        verifyNoInteractions(service);
    }

    @Test
    void signaturePrecedesSessionAndRejectsTokenOnlyTamperingExpiryAndReplay() throws Exception {
        var unsigned = new MockHttpServletRequest("POST", "/internal/ai-observability/register");
        unsigned.addHeader("satoken", "token"); unsigned.setContent(registration().getBytes(StandardCharsets.UTF_8));
        status(() -> controller.register(unsigned), 403);
        var tampered = signed("register", registration(), "token", clock);
        tampered.setContent("{}".getBytes(StandardCharsets.UTF_8));
        status(() -> controller.register(tampered), 403);
        status(() -> controller.register(signed("register", registration(), "token", Clock.offset(clock, java.time.Duration.ofSeconds(-61)))), 403);
        verifyNoInteractions(service);
        when(service.register(any(), eq("token"))).thenReturn(row());
        var original = signed("register", registration(), "token", clock);
        controller.register(original);
        var replay = signed("register", registration(), "token", clock);
        replay.removeHeader("X-Service-Nonce"); replay.removeHeader("X-Service-Signature");
        replay.addHeader("X-Service-Nonce", original.getHeader("X-Service-Nonce"));
        replay.addHeader("X-Service-Signature", original.getHeader("X-Service-Signature"));
        status(() -> controller.register(replay), 403);
        verify(service, times(1)).register(any(), eq("token"));
    }

    @Test
    void strictBodiesNeverPassSensitiveUnknownDuplicateOrOversizedInput() throws Exception {
        for (String body : new String[]{registration().replace("\"version\":1", "\"version\":1,\"version\":1"),
                registration().replace("\"version\":1", "\"version\":1,\"userId\":8"),
                registration().replace("\"version\":1", "\"version\":1,\"question\":\"private\""), " ".repeat(32769)}) {
            status(() -> controller.register(signed("register", body, "token", clock)), 400);
        }
        verifyNoInteractions(service);
    }

    @Test
    void signedRegistrationDeliversTokenAndPropagatesSubjectRefusal() throws Exception {
        when(service.register(any(), eq("token"))).thenReturn(row());
        controller.register(signed("register", registration(), "token", clock));
        verify(service).register(any(), eq("token"));
        doThrow(ApiException.forbidden("停用")).when(service).register(any(), eq("token"));
        status(() -> controller.register(signed("register", registration(), "token", clock)), 403);
    }

    @Test
    void fixedEventsIgnoreExpiredUserSessionButNeverCreateMissingCalls() throws Exception {
        var event = new AiObservation.Event(1, id, AiObservation.eventId(id, "REVIEW"), 1, "REVIEW", clock.instant(),
                null, null, null, null, null, null, null, null, null);
        String body = json.writeValueAsString(event);
        when(service.events(event)).thenReturn(row());
        controller.events(signed("events", body, null, clock));
        verify(service).events(event);
        doThrow(ApiException.notFound("missing")).when(service).events(event);
        status(() -> controller.events(signed("events", body, null, clock)), 404);
        verify(service, times(2)).events(event);
    }

    private AiCallRow row() {
        var row = new AiCallRow(); row.setCallId(id.toString()); row.setEpoch(1); row.setUserId(5); row.setDepartmentId(6);
        row.setMode("SINGLE"); row.setGenerationStatus("RUNNING"); row.setSettlementStatus("NOT_REQUIRED"); return row;
    }

    private static void status(org.assertj.core.api.ThrowableAssert.ThrowingCallable work, int code) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(code));
    }
}
