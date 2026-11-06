package com.zhishiku.kb.ai.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPrivacyFilterTest {
    @Test
    void singleAndMultiTurnSuccessAndFailureResponsesCannotBeStored() {
        var filter = new ConversationPrivacyFilter();
        for (String path : List.of("/api/ai/chat", "/api/ai/conversations/9/turns", "/internal/conversations/settle")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path));
            filter.filter(exchange, request -> Mono.empty()).block();
            assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            var oversized = MockServerWebExchange.from(MockServerHttpRequest.post(path).header("Content-Length", "262145"));
            filter.filter(oversized, request -> Mono.error(new AssertionError("不应进入业务链"))).block();
            assertThat(oversized.getResponse().getStatusCode().value()).isEqualTo(413);
            assertThat(oversized.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }
}
