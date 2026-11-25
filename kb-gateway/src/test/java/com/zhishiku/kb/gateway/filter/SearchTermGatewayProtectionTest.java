package com.zhishiku.kb.gateway.filter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立过滤器与模拟 Exchange，不监听端口或组装网关。 */
class SearchTermGatewayProtectionTest {
    @Test
    void identityHeadersAreStrippedWithoutLoggingValuesOrQueries() {
        String probe = "PRIVATE_QUERY_TERM_TOKEN";
        var log = (Logger) LoggerFactory.getLogger(InboundHeaderSanitizeFilter.class);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        log.addAppender(captured);
        try {
            for (String path : List.of("/api/search/assisted", "/api/admin/search-terms/unknown/" + probe,
                    "/api/admin/spaces/10/authorization-diagnostics/unknown/" + probe)) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path + "?query=" + probe)
                        .header("X-User-Id", probe).header("X-Role-Ids", probe).header("satoken", probe));
                new InboundHeaderSanitizeFilter().filter(exchange, target -> {
                    assertThat(target.getRequest().getHeaders()).doesNotContainKeys("X-User-Id", "X-Role-Ids");
                    assertThat(target.getRequest().getHeaders().getFirst("satoken")).isEqualTo(probe);
                    return Mono.empty();
                }).block();
            }
            assertThat(captured.list).hasSize(3).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(probe);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            log.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void similarManagementPrefixIsNotGivenSearchResponsePolicy() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/admin/search-terms-other"));
        new SearchResponsePolicy().filter(exchange, target -> target.getResponse().setComplete()).block();
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isNull();
    }
}
