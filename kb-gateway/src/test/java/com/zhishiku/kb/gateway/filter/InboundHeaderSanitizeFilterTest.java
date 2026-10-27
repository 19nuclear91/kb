package com.zhishiku.kb.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InboundHeaderSanitizeFilterTest {

    @Test
    void stripsAllClientIdentityHeadersBeforeRouting() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/search")
                .header("X-Dev-User-Id", "99")
                .header("X-User-Id", "99")
                .header("X-Dept-Id", "99")
                .header("X-Role-Ids", "1")
                .header("X-Clearance-Level", "3")
                .header("X-Principal-Keys", "3:99")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        AtomicReference<ServerHttpRequest> forwarded = new AtomicReference<>();
        GatewayFilterChain chain = next -> {
            forwarded.set(next.getRequest());
            return Mono.empty();
        };

        new InboundHeaderSanitizeFilter().filter(exchange, chain).block();

        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get().getHeaders()).doesNotContainKey("X-Dev-User-Id");
        assertThat(forwarded.get().getHeaders()).doesNotContainKey("X-Dept-Id");
        assertThat(forwarded.get().getHeaders()).doesNotContainKey("X-Principal-Keys");
    }

    @Test
    void controlledGatewayLogContainsPathButNeverQueryString() {
        String canary = "QUERY_CANARY_938";
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/search?q=" + canary)
                .header("X-User-Id", canary).build();
        Logger logger = (Logger) LoggerFactory.getLogger(InboundHeaderSanitizeFilter.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            new InboundHeaderSanitizeFilter().filter(MockServerWebExchange.from(request),
                    next -> Mono.empty()).block();
            assertThat(captured.list).hasSize(1);
            assertThat(captured.list.getFirst().getFormattedMessage())
                    .contains("path=/api/search").doesNotContain(canary, "?q=");
            assertThat(captured.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }
}
