package com.zhishiku.kb.ai.chat;

import com.sun.net.httpserver.HttpServer;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaPrincipalClientIntegrationTest {

    @Test
    void carriesSessionToKbAppAndUsesResolvedDepartment() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/auth/quota-context", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("satoken"));
            byte[] body = "{\"code\":200,\"message\":\"ok\",\"data\":{\"userId\":7,\"departmentId\":42}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            AiProperties props = new AiProperties(
                    "http://localhost:" + server.getAddress().getPort(), 8, 60_000L, 5);
            QuotaPrincipalClient client = new QuotaPrincipalClient(WebClient.builder(), props);

            assertThat(client.resolve("trusted-session").block(Duration.ofSeconds(3)))
                    .isEqualTo(new QuotaPrincipal(7L, 42L));
            assertThat(token).hasValue("trusted-session");
        } finally {
            server.stop(0);
        }
    }
}
