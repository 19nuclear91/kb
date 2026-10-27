package com.zhishiku.kb.app.conversation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只投递固定账本字段，绝不读取历史或发起生成。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class SettlementDeliveryClient {
    private final RestClient client;
    private final ConversationProperties properties;
    private final ObjectMapper json;

    public SettlementDeliveryClient(RestClient.Builder builder, ConversationProperties properties, ObjectMapper json,
                                    @Value("${kb.conversation.ai-base-url:}") String baseUrl) {
        var factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.properties = properties;
        this.json = json;
    }

    public void deliver(SettlementRow row) {
        try {
            byte[] body = json.writeValueAsBytes(payload(row));
            String path = "/internal/conversations/settle";
            var signed = RequestSignature.sign(properties.getSecret(), "kb-app", "POST", path, body, null,
                    Clock.systemUTC());
            client.post().uri(path).contentType(MediaType.APPLICATION_JSON).headers(headers -> {
                headers.set("X-Service-Caller", signed.caller());
                headers.set("X-Service-Timestamp", Long.toString(signed.timestamp()));
                headers.set("X-Service-Nonce", signed.nonce());
                headers.set("X-Service-Signature", signed.signature());
            }).body(body).retrieve().toBodilessEntity();
        } catch (Exception error) {
            throw new IllegalStateException("结算投递暂未确认");
        }
    }

    public static Map<String, Object> payload(SettlementRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("turnId", row.getTurnId());
        map.put("userId", row.getUserId());
        map.put("departmentId", row.getDepartmentId());
        map.put("leaseId", row.getLeaseId());
        map.put("eventId", row.getEventId());
        map.put("reservedTokens", row.getReservedTokens());
        map.put("usageStatus", row.getUsageStatus());
        map.put("promptTokens", row.getPromptTokens());
        map.put("completionTokens", row.getCompletionTokens());
        map.put("chargedTokens", row.getChargedTokens());
        map.put("generationStatus", row.getGenerationStatus());
        map.put("intentDigest", row.getIntentDigest());
        map.put("finalizedAt", row.getFinalizedAt());
        return map;
    }
}

