package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.NotificationContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSubscriptionResponsePolicyTest {
    @Test
    void privateResponsesRemainNoStoreIncludingEarlyAuthenticationFailures() {
        for (String path : List.of("/api/knowledge-subscriptions", "/api/knowledge-subscription-preferences", "/api/knowledge-updates/id", "/api/knowledge-digests/id")) {
            for (var status : List.of(HttpStatus.OK, HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                    HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.SERVICE_UNAVAILABLE)) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
                new KnowledgeSubscriptionResponsePolicy().filter(exchange, current -> {
                    current.getResponse().setStatusCode(status);
                    current.getResponse().getHeaders().setCacheControl("public");
                    return current.getResponse().setComplete();
                }).block(Duration.ofSeconds(1));
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
                assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            }
        }
    }

    @Test
    void newNotificationContractPassesSanitizationWithoutGrantingForgedIdentity() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/notifications")
                .header(NotificationContract.HEADER, "KNOWLEDGE_SUBSCRIPTIONS_V1").header("X-User-Id", "forged"));
        new NotificationResponsePolicy().filter(exchange, current -> new InboundHeaderSanitizeFilter().filter(current, clean -> {
            assertThat(clean.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
            assertThat(clean.getRequest().getHeaders().getFirst(NotificationContract.HEADER)).isEqualTo("KNOWLEDGE_SUBSCRIPTIONS_V1");
            return clean.getResponse().setComplete();
        })).block(Duration.ofSeconds(1));
    }
}
