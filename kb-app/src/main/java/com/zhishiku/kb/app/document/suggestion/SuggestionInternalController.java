package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@RestController
public class SuggestionInternalController {
    private static final Set<String> ACTIONS = Set.of("reserve", "reserved", "permit", "active");
    private final SuggestionExecution execution;
    private final ObjectMapper json;
    private final String secret;
    private final StringRedisTemplate redis;

    public SuggestionInternalController(SuggestionExecution execution, ObjectMapper json,
                                       @Value("${kb.metadata-suggestions.secret:}") String secret, StringRedisTemplate redis) {
        this.execution = execution;
        this.json = json;
        this.secret = secret;
        this.redis = redis;
    }

    @PostMapping("/internal/metadata-suggestions/{operation}")
    public ResponseEntity<?> act(@PathVariable String operation, HttpServletRequest request) {
        try {
            if (!ACTIONS.contains(operation) || request.getQueryString() != null) {
                return ResponseEntity.badRequest().build();
            }
            byte[] body = request.getInputStream().readNBytes(4097);
            if (body.length > 4096) {
                return ResponseEntity.status(413).build();
            }
            String token = request.getHeader("satoken");
            var signature = new RequestSignature.Headers(request.getHeader("X-Service-Caller"),
                    Long.parseLong(request.getHeader("X-Service-Timestamp")), request.getHeader("X-Service-Nonce"),
                    request.getHeader("X-Service-Signature"));
            SuggestionProtocol.verify(secret, "kb-ai", operation, body, token, signature, Clock.systemUTC(),
                    (caller, nonce, ttl) -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                            "kb:metadata-suggestions:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))));
            var action = json.readValue(body, SuggestionProtocol.Action.class);
            try {
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(execution.action(operation, token, action));
            } catch (RuntimeException denied) {
                return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("reason", SuggestionExecution.reason(denied)));
            }
        } catch (SecurityException | NumberFormatException denied) {
            return ResponseEntity.status(403).build();
        } catch (Exception failed) {
            return ResponseEntity.status(503).build();
        }
    }
}
