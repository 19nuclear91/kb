package com.zhishiku.kb.app.aiusage.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.service.AiUsageWriter;
import com.zhishiku.kb.app.aiusage.service.AiObservationService;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.conversation.RequestSignature;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 独立服务鉴权；后续固定收尾不依赖原用户会话。
 */
@RestController
@AdminQueryEndpoint
@RequestMapping("/internal/ai-observability")
public class AiObservationController {
    private final AiObservationProperties properties;
    private final AiObservationService service;
    private final ObjectMapper json;
    private final RequestSignature.NonceStore nonces;
    private final Clock clock;

    @Autowired
    public AiObservationController(AiObservationProperties properties, AiObservationService service, ObjectMapper json,
                                   StringRedisTemplate redis) {
        this(properties, service, json, (caller, nonce, ttl) -> Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent("kb:ai:observation:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))), Clock.systemUTC());
    }

    public AiObservationController(AiObservationProperties properties, AiObservationService service, ObjectMapper json,
                                   RequestSignature.NonceStore nonces, Clock clock) {
        this.properties = properties; this.service = service; this.nonces = nonces; this.clock = clock;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                .maxStringLength(256).maxNumberLength(20).build());
    }

    @PostMapping(path = "/register", consumes = "application/json")
    public R<Map<String, Object>> register(HttpServletRequest request) {
        var body = authenticated(request);
        var registration = read(body, AiObservation.Registration.class);
        var row = service.register(registration, request.getHeader("satoken"));
        return R.ok(reference(row));
    }

    @PostMapping(path = "/events", consumes = "application/json")
    public R<Map<String, Object>> events(HttpServletRequest request) {
        var body = authenticated(request);
        var event = read(body, AiObservation.Event.class);
        var result = new java.util.LinkedHashMap<>(reference(service.events(event)));
        result.put("acceptedEventId", event.eventId().toString());
        result.put("acceptedDigest", event.digest());
        return R.ok(result);
    }

    @PostMapping(path = "/renew", consumes = "application/json")
    public R<Map<String, Boolean>> renew(HttpServletRequest request) {
        var ref = read(authenticated(request), Reference.class);
        service.renew(ref.callId(), ref.epoch());
        return R.ok(Map.of("accepted", true));
    }

    @PostMapping(path = "/status", consumes = "application/json")
    public R<Map<String, Object>> status(HttpServletRequest request) {
        var ref = read(authenticated(request), Reference.class);
        var row = service.status(ref.callId(), ref.epoch());
        return R.ok(reference(row));
    }

    private byte[] authenticated(HttpServletRequest request) {
        if (properties.getSecret() == null || properties.getSecret().isBlank()
                || properties.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32)
            throw ApiException.unavailable("AI 观测服务签名尚未配置");
        byte[] body;
        try { body = request.getInputStream().readNBytes(32769); }
        catch (IOException error) { throw AiUsageWriter.invalid(); }
        if (body.length > 32768) throw AiUsageWriter.invalid();
        try {
            var signed = new RequestSignature.Headers(request.getHeader("X-Service-Caller"),
                    Long.parseLong(request.getHeader("X-Service-Timestamp")), request.getHeader("X-Service-Nonce"),
                    request.getHeader("X-Service-Signature"));
            RequestSignature.verify(properties.getSecret(), "kb-ai", request.getMethod(), request.getRequestURI(),
                    body, request.getHeader("satoken"), signed, clock, properties.getSignatureWindowSeconds(),
                    properties.getNonceTtlSeconds(), nonces);
        } catch (SecurityException | IllegalArgumentException error) {
            throw ApiException.forbidden("AI 内部采集身份无效");
        }
        return body;
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            T value = json.readValue(body, type);
            if (value == null) throw AiUsageWriter.invalid();
            return value;
        }
        catch (IOException | IllegalArgumentException error) { throw AiUsageWriter.invalid(); }
    }

    private static Map<String, Object> reference(AiCallRow row) {
        return Map.of("callId", row.getCallId(), "epoch", row.getEpoch(), "userId", row.getUserId(),
                "departmentId", row.getDepartmentId(), "generationStatus", row.getGenerationStatus(),
                "settlementStatus", row.getSettlementStatus());
    }

    /**
     * 仅引用已经登记的固定执行。
     * @param callId 调用身份
     * @param epoch 执行代次
     */
    public record Reference(UUID callId, long epoch) {
        public Reference { if (callId == null || epoch < 1) throw AiUsageWriter.invalid(); }
    }
}
