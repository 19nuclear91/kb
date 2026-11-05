package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeServiceSignature;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** 不使用自动观测的全局客户端，避免请求正文或浏览器证明进入外部遥测。 */
@Component
public class OfficeAuthorizationClient {
    private final OfficeGatewaySettings settings;
    private final ObjectMapper json;
    private final WebClient client;
    private final ConnectionProvider pool;

    @Autowired
    public OfficeAuthorizationClient(OfficeGatewaySettings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
        this.pool = ConnectionProvider.builder("office-authorization").maxConnections(16)
                .pendingAcquireMaxCount(32).pendingAcquireTimeout(Duration.ofSeconds(2)).build();
        this.client = WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool)
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).followRedirect(false)
                        .responseTimeout(Duration.ofSeconds(settings.getAuthorizationTimeoutSeconds()))))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536)).build();
    }

    public OfficeAuthorizationClient(OfficeGatewaySettings settings, ObjectMapper json, WebClient client) {
        this.settings = settings;
        this.json = json;
        this.client = client;
        this.pool = null;
    }

    public Mono<JsonNode> authorize(String id, String cookie) {
        return call("authorize", Map.of("sessionId", id, "cookie", cookie));
    }

    public Mono<JsonNode> call(String operation, Map<String, String> fields) {
        return Mono.defer(() -> {
            if (!settings.isEnabled()) {
                return Mono.error(new OfficeProxyFailure(503));
            }
            try {
                String path = "/internal/office-preview/" + operation;
                byte[] body = json.writeValueAsBytes(fields);
                String proof = OfficeServiceSignature.sign(settings.getGatewaySecret(), path, body, Clock.systemUTC());
                return client.post().uri(URI.create(settings.getAppBaseUrl() + path)).contentType(MediaType.APPLICATION_JSON)
                        .header(OfficeServiceSignature.HEADER, proof).bodyValue(body)
                        .exchangeToMono(response -> {
                            if (!response.statusCode().is2xxSuccessful()) {
                                return response.releaseBody().then(Mono.error(new OfficeProxyFailure(response.statusCode().value())));
                            }
                            return response.bodyToMono(JsonNode.class).filter(JsonNode::isObject)
                                    .switchIfEmpty(Mono.error(new OfficeProxyFailure(503)));
                        });
            } catch (Exception invalid) {
                return Mono.error(new OfficeProxyFailure(503));
            }
        }).timeout(Duration.ofSeconds(settings.getAuthorizationTimeoutSeconds()))
                .onErrorMap(error -> error instanceof OfficeProxyFailure ? error : new OfficeProxyFailure(503));
    }

    @PreDestroy
    public void close() {
        if (pool != null) {
            pool.dispose();
        }
    }
}
