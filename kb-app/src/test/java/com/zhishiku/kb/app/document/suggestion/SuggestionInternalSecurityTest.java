package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class SuggestionInternalSecurityTest {
    private final String secret = "a-dedicated-signing-test-key-00000000000";
    private final String token = "session-canary";
    private MockHttpServletRequest signed(String caller, Clock clock, byte[] bytes) {
        var req = new MockHttpServletRequest();
        String path = SuggestionProtocol.ROOT + "permit";
        var signature = RequestSignature.sign(secret, caller, "POST", path, bytes, token, clock);
        req.addHeader("satoken", token);
        req.addHeader("X-Service-Caller", caller);
        req.addHeader("X-Service-Nonce", signature.nonce());
        req.addHeader("X-Service-Timestamp", signature.timestamp());
        req.addHeader("X-Service-Signature", signature.signature());
        req.setContent(bytes);
        return req;
    }

    @Test
    @SuppressWarnings("unchecked")
    void signatureCoversCallerFreshnessSessionBodyAndNonce(CapturedOutput output) throws Exception {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var claimed = new HashSet<String>();
        when(values.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenAnswer(call -> claimed.add(call.getArgument(0)));
        var service = mock(SuggestionExecution.class);
        var json = new ObjectMapper();
        var controller = new SuggestionInternalController(service, json, secret, redis);
        var action = new SuggestionProtocol.Action(new SuggestionProtocol.Key(UUID.randomUUID(), UUID.randomUUID(), 1, 1), 50);
        var body = json.writeValueAsBytes(action);
        var now = Clock.systemUTC();
        assertThat(controller.act("permit", signed("kb-app", now, body)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.act("permit", signed("kb-ai", Clock.offset(now, Duration.ofMinutes(-5)), body)).getStatusCode().value()).isEqualTo(403);
        var changed = signed("kb-ai", now, body);
        changed.setContent("私有正文".getBytes());
        assertThat(controller.act("permit", changed).getStatusCode().value()).isEqualTo(403);
        when(service.action("permit", token, action)).thenThrow(new IllegalStateException("私有正文和凭据"));
        var valid = signed("kb-ai", now, body);
        var failure = controller.act("permit", valid);
        assertThat(String.valueOf(failure.getBody())).contains("UNAVAILABLE").doesNotContain("私有正文");
        valid.setContent(body);
        assertThat(controller.act("permit", valid).getStatusCode().value()).isEqualTo(403);
        verify(service).action("permit", token, action);
        assertThat(output.getAll()).doesNotContain("私有正文", token, secret);
    }

    @Test
    void recoveryReportsFailureWithoutExceptionPayload(CapturedOutput output) {
        var store = mock(SuggestionStore.class);
        when(store.expiredExecutions()).thenThrow(new IllegalStateException("正文机密、分类机密、供应商凭据"));
        var runner = new SuggestionRunner(mock(SuggestionMaterials.class), mock(SuggestionExecution.class), store,
                new ObjectMapper(), mock(com.zhishiku.kb.common.suggestion.SuggestionHttp.class));
        try {
            runner.recover();
            assertThat(output.getAll()).contains("整理恢复扫描暂不可用").doesNotContain("正文机密", "分类机密", "供应商凭据");
        } finally {
            runner.close();
        }
    }

    @Test
    void eventAndDiagnosticRepresentationsExcludePrivateData() {
        UUID id = UUID.randomUUID();
        var start = new SuggestionRequests.Start(id, UUID.randomUUID(), token);
        var text = new SuggestionData.Text("模型输出机密", List.of(new SuggestionData.Source(0, "章节机密", 2)));
        var result = new SuggestionData.Result(text, text, new SuggestionData.Classification(
                new SuggestionData.Category(1, List.of("分类机密")), List.of()));
        var outcome = new SuggestionProtocol.Outcome(null, 8L, 1L, result);
        assertThat(start.toString()).contains(id.toString()).doesNotContain(token);
        assertThat(outcome.toString()).doesNotContain("模型输出机密", "章节机密", "分类机密");
    }
}
