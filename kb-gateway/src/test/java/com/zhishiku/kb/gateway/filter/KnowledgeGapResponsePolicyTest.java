package com.zhishiku.kb.gateway.filter;

import com.zhishiku.kb.common.security.NotificationContract;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 内存交换与身份/下游替身，不启动网关或调用 Redis。 */
class KnowledgeGapResponsePolicyTest {
    @Test
    void successAndAllErrorBodiesStayJsonNoStoreWithoutDownstreamMutation() {
        for (String path : List.of("/api/knowledge-gaps", "/api/knowledge-gaps/1/unknown", "/api/admin/knowledge-gaps/1/assign", "/api/knowledge%2Dgaps/1")) {
            for (HttpStatus status : List.of(HttpStatus.OK, HttpStatus.CREATED, HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                    HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.CONFLICT, HttpStatus.PAYLOAD_TOO_LARGE, HttpStatus.SERVICE_UNAVAILABLE)) {
                var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
                assertThat(exchange.getRequest().getURI().getRawPath()).isEqualTo(path);
                new KnowledgeGapResponsePolicy().filter(exchange, current -> {
                    current.getResponse().setStatusCode(status);
                    current.getResponse().getHeaders().setCacheControl("public");
                    current.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return current.getResponse().writeWith(Mono.just(current.getResponse().bufferFactory()
                            .wrap("{\"code\":500,\"data\":null}".getBytes(StandardCharsets.UTF_8))));
                }).block(Duration.ofSeconds(1));
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
                assertThat(exchange.getResponse().getHeaders().getCacheControl()).as("%s %s", path, status).isEqualTo("no-store");
                assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"code\":500,\"data\":null}");
            }
        }
    }

    @Test
    void earlyAuthenticationOrBodyDenialDoesNotReachDownstreamAndAiIsUntouched() {
        var downstream = mock(WebFilterChain.class);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/knowledge-gaps"));
        new KnowledgeGapResponsePolicy().filter(exchange, current -> {
            assertThat(current.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
            current.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return current.getResponse().setComplete();
        }).block(Duration.ofSeconds(1));
        verifyNoInteractions(downstream);
        assertThat(exchange.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
        var ai = MockServerWebExchange.from(MockServerHttpRequest.post("/api/ai/chat"));
        new KnowledgeGapResponsePolicy().filter(ai, current -> current.getResponse().setComplete()).block(Duration.ofSeconds(1));
        assertThat(ai.getResponse().getHeaders().getCacheControl()).isNull();
    }

    @Test
    void legalCapabilityPassesThroughSanitizationWhileInvalidValuesNeverReachDownstream() {
        for (String path : List.of("/api/notifications", "/api/notifications/unread-count")) {
            var valid = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                    .header(NotificationContract.HEADER, "KNOWLEDGE_GAPS_V1").header("X-User-Id", "secret forged actor"));
            new NotificationResponsePolicy().filter(valid, current -> new InboundHeaderSanitizeFilter().filter(current, sanitized -> {
                assertThat(sanitized.getRequest().getHeaders().get(NotificationContract.HEADER)).containsExactly("KNOWLEDGE_GAPS_V1");
                assertThat(sanitized.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
                return sanitized.getResponse().setComplete();
            })).block(Duration.ofSeconds(1));
            for (String[] values : List.of(new String[]{"OTHER secret"}, new String[]{""}, new String[]{" KNOWLEDGE_GAPS_V1"},
                    new String[]{"KNOWLEDGE_GAPS_V1,KNOWLEDGE_GAPS_V1"}, new String[]{"KNOWLEDGE_GAPS_V1", "KNOWLEDGE_GAPS_V1"})) {
                var downstream = mock(WebFilterChain.class);
                var invalid = MockServerWebExchange.from(MockServerHttpRequest.get(path).header(NotificationContract.HEADER, values));
                new NotificationResponsePolicy().filter(invalid, downstream).block(Duration.ofSeconds(1));
                verifyNoInteractions(downstream);
                assertThat(invalid.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(invalid.getResponse().getHeaders().getCacheControl()).isEqualTo("no-store");
                assertThat(invalid.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
                assertThat(invalid.getResponse().getBodyAsString().block()).doesNotContain("secret", "OTHER", "html");
            }
        }
        var read = MockServerWebExchange.from(MockServerHttpRequest.put("/api/notifications/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/read")
                .header(NotificationContract.HEADER, "OTHER"));
        new NotificationResponsePolicy().filter(read, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete();
        }).block(Duration.ofSeconds(1));
        assertThat(read.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
