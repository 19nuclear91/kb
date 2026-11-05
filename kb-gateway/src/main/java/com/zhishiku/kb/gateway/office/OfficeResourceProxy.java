package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeProtocol;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.WebsocketServerSpec;
import reactor.netty.resources.ConnectionProvider;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OfficeResourceProxy {
    private final OfficeGatewaySettings settings;
    private final OfficeAuthorizationClient authorization;
    private final ObjectMapper json;
    private final OfficeWireProtocol protocol;
    private final OfficeConnections connections;
    private final WebClient http;
    private final WebSocketClient sockets;
    private final WebSocketService upgrade;
    private final ConnectionProvider pool;
    private final Semaphore requests;
    private final MeterRegistry metrics;

    @Autowired
    public OfficeResourceProxy(OfficeGatewaySettings settings, OfficeAuthorizationClient authorization,
                               ObjectMapper json, MeterRegistry metrics) {
        this.settings = settings;
        this.authorization = authorization;
        this.json = json;
        this.metrics = metrics;
        protocol = new OfficeWireProtocol(json, settings);
        connections = new OfficeConnections(settings.getMaxConnections(), Clock.systemUTC());
        requests = new Semaphore(settings.getMaxConnections() * 4);
        pool = ConnectionProvider.builder("office-resources").maxConnections(settings.getMaxConnections())
                .pendingAcquireMaxCount(settings.getMaxConnections()).pendingAcquireTimeout(Duration.ofSeconds(2)).build();
        HttpClient client = HttpClient.create(pool).followRedirect(false).compress(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(settings.getUpstreamTimeoutSeconds()));
        http = WebClient.builder().clientConnector(new ReactorClientHttpConnector(client))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(settings.getMaxMessageBytes())).build();
        sockets = new ReactorNettyWebSocketClient(client, () -> WebsocketClientSpec.builder()
                .maxFramePayloadLength(settings.getMaxMessageBytes()));
        upgrade = new HandshakeWebSocketService(new ReactorNettyRequestUpgradeStrategy(() -> WebsocketServerSpec.builder()
                .maxFramePayloadLength(settings.getMaxMessageBytes())));
    }

    OfficeResourceProxy(OfficeGatewaySettings settings, OfficeAuthorizationClient authorization, ObjectMapper json,
                        MeterRegistry metrics, WebClient http, WebSocketClient sockets, WebSocketService upgrade) {
        this.settings = settings;
        this.authorization = authorization;
        this.json = json;
        this.metrics = metrics;
        this.http = http;
        this.sockets = sockets;
        this.upgrade = upgrade;
        this.pool = null;
        protocol = new OfficeWireProtocol(json, settings);
        connections = new OfficeConnections(settings.getMaxConnections(), Clock.systemUTC());
        requests = new Semaphore(settings.getMaxConnections() * 4);
    }

    public Mono<Void> proxy(ServerWebExchange exchange) {
        return Mono.defer(() -> {
            String id = OfficePreviewExchange.sessionId(exchange, settings);
            String cookie = OfficePreviewExchange.cookie(exchange);
            String path = exchange.getRequest().getURI().getRawPath().substring(("/s/" + id).length());
            return authorization.authorize(id, cookie).flatMap(grant -> {
                String key = grant.path("documentKey").asText();
                OfficeProtocol.Resource resource = OfficeProtocol.resource(exchange.getRequest().getMethod().name(), path, key);
                if (resource == OfficeProtocol.Resource.DENIED) {
                    return Mono.error(new OfficeProxyFailure(404));
                }
                Map<String, String> query = protocol.query(exchange.getRequest().getURI().getRawQuery(), resource == OfficeProtocol.Resource.SOCKET, key);
                if (resource == OfficeProtocol.Resource.SOCKET) {
                    boolean websocket = "websocket".equals(query.get("transport"));
                    if ((websocket || exchange.getRequest().getMethod() == HttpMethod.POST)
                            && !settings.getPreviewOrigin().equals(exchange.getRequest().getHeaders().getOrigin())) {
                        return Mono.error(new OfficeProxyFailure(404));
                    }
                    connections.enter(id, query.get("sid"), grant.path("expiresAt").asLong(), websocket);
                    if (websocket) {
                        if (!"websocket".equalsIgnoreCase(exchange.getRequest().getHeaders().getUpgrade())
                                || exchange.getRequest().getMethod() != HttpMethod.GET) {
                            connections.remove(id);
                            return Mono.error(new OfficeProxyFailure(404));
                        }
                        return websocket(exchange, path, id, cookie).doFinally(signal -> connections.remove(id));
                    }
                }
                if (!requests.tryAcquire()) {
                    return Mono.error(new OfficeProxyFailure(429));
                }
                return http(exchange, path, resource, id, cookie).doFinally(signal -> requests.release());
            });
        }).doOnError(this::failure);
    }

    private Mono<Void> http(ServerWebExchange exchange, String path, OfficeProtocol.Resource resource, String id, String cookie) {
        boolean polling = resource == OfficeProtocol.Resource.SOCKET;
        Mono<byte[]> body = exchange.getRequest().getMethod() == HttpMethod.POST
                ? OfficePreviewExchange.readBody(exchange, settings.getMaxMessageBytes()).flatMap(bytes -> packets(bytes, true, id, cookie))
                : Mono.just(new byte[0]);
        return body.flatMap(bytes -> {
            var request = http.method(exchange.getRequest().getMethod()).uri(target(exchange, path, false))
                    .headers(headers -> headers.addAll(upstreamHeaders(id)));
            if (bytes.length > 0) {
                request.contentType(MediaType.TEXT_PLAIN).bodyValue(bytes);
            }
            return request.exchangeToMono(response -> {
                if (!response.statusCode().is2xxSuccessful() || response.headers().header("Location").size() > 0) {
                    return response.releaseBody().then(Mono.error(new OfficeProxyFailure(503)));
                }
                if (polling) {
                    return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(output -> {
                        if (exchange.getRequest().getMethod() == HttpMethod.POST) {
                            if (!"ok".equals(new String(output, StandardCharsets.UTF_8))) {
                                return Mono.error(new OfficeProxyFailure(503));
                            }
                            return write(exchange, output);
                        }
                        return packets(output, false, id, cookie).flatMap(clean -> write(exchange, clean));
                    });
                }
                long length = response.headers().contentLength().orElse(-1);
                if (length > settings.getMaxResourceBytes()) {
                    return response.releaseBody().then(Mono.error(new OfficeProxyFailure(503)));
                }
                exchange.getResponse().getHeaders().setContentType(response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM));
                AtomicLong count = new AtomicLong();
                Flux<DataBuffer> stream = response.bodyToFlux(DataBuffer.class).handle((buffer, sink) -> {
                    if (count.addAndGet(buffer.readableByteCount()) > settings.getMaxResourceBytes()) {
                        DataBufferUtils.release(buffer);
                        sink.error(new OfficeProxyFailure(503));
                    } else {
                        sink.next(buffer);
                    }
                });
                return exchange.getResponse().writeWith(stream.doOnDiscard(DataBuffer.class, DataBufferUtils::release));
            });
        }).timeout(Duration.ofSeconds(settings.getUpstreamTimeoutSeconds()))
                .doOnError(error -> {
                    if (polling) {
                        connections.remove(id);
                    }
                });
    }

    private Mono<byte[]> packets(byte[] bytes, boolean fromBrowser, String id, String cookie) {
        if (bytes.length > settings.getMaxMessageBytes()) {
            return Mono.error(new OfficeProxyFailure(415));
        }
        String[] frames = new String(bytes, StandardCharsets.UTF_8).split("\u001e", -1);
        if (frames.length > 256) {
            return Mono.error(new OfficeProxyFailure(415));
        }
        return Flux.fromArray(frames).concatMap(frame -> frame(frame, fromBrowser, id, cookie), 1)
                .collectList().map(parts -> String.join("\u001e", parts).getBytes(StandardCharsets.UTF_8));
    }

    Mono<String> frame(String text, boolean fromBrowser, String id, String cookie) {
        return authorization.authorize(id, cookie).map(grant -> {
            String clean = protocol.frame(text, fromBrowser, grant, id);
            connections.renew(id, grant.path("expiresAt").asLong());
            if (!fromBrowser && clean.startsWith("0")) {
                try {
                    connections.opened(id, json.readTree(clean.substring(1)).path("sid").asText());
                } catch (OfficeProxyFailure failure) {
                    throw failure;
                } catch (Exception invalid) {
                    throw new OfficeProxyFailure(415);
                }
            }
            if (clean.equals("1")) {
                connections.remove(id);
            }
            return clean;
        });
    }

    private Mono<Void> websocket(ServerWebExchange exchange, String path, String id, String cookie) {
        return upgrade.handleRequest(exchange, browser -> sockets.execute(target(exchange, path, true), upstreamHeaders(id), vendor -> {
            Mono<Void> outgoing = vendor.send(messages(browser, vendor, true, id, cookie));
            Mono<Void> incoming = browser.send(messages(vendor, browser, false, id, cookie));
            Mono<Void> lease = Flux.interval(Duration.ofSeconds(10)).concatMap(tick -> authorization.authorize(id, cookie)
                    .doOnNext(grant -> connections.renew(id, grant.path("expiresAt").asLong())), 1).then();
            return Mono.firstWithSignal(outgoing, incoming, lease)
                    .then(Mono.when(browser.close(CloseStatus.NORMAL), vendor.close(CloseStatus.NORMAL)))
                    .onErrorResume(error -> {
                        failure(error);
                        return Mono.whenDelayError(browser.close(CloseStatus.POLICY_VIOLATION), vendor.close(CloseStatus.POLICY_VIOLATION));
                    });
        }).onErrorResume(error -> {
            failure(error);
            return browser.close(CloseStatus.POLICY_VIOLATION);
        }));
    }

    Flux<WebSocketMessage> messages(WebSocketSession source, WebSocketSession target, boolean fromBrowser, String id, String cookie) {
        // Spring/Netty 在有限的消息长度内聚合分片；先复制文本，再跨异步授权边界。
        return source.receive().map(message -> {
            if (message.getType() != WebSocketMessage.Type.TEXT || message.getPayload().readableByteCount() > settings.getMaxMessageBytes()) {
                throw new OfficeProxyFailure(415);
            }
            return message.getPayloadAsText(StandardCharsets.UTF_8);
        }).concatMap(text -> frame(text, fromBrowser, id, cookie).map(target::textMessage), 1);
    }

    HttpHeaders upstreamHeaders(String id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-Host", URI.create(settings.getPreviewOrigin()).getRawAuthority() + "/s/" + id);
        headers.set("X-Forwarded-Proto", "https");
        headers.setOrigin(settings.getPreviewOrigin());
        return headers;
    }

    private URI target(ServerWebExchange exchange, String path, boolean socket) {
        String base = settings.getUpstreamUrl();
        if (socket) {
            base = base.replaceFirst("^http", "ws");
        }
        String query = exchange.getRequest().getURI().getRawQuery();
        return URI.create(base + path + (query == null ? "" : "?" + query));
    }

    private Mono<Void> write(ServerWebExchange exchange, byte[] body) {
        exchange.getResponse().getHeaders().setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private void failure(Throwable error) {
        String category = error instanceof OfficeProxyFailure failure ? Integer.toString(failure.status()) : "503";
        metrics.counter("kb.office.preview.failures", "route", "office-resource", "status", category).increment();
    }

    @PreDestroy
    public void close() {
        if (pool != null) {
            pool.dispose();
        }
    }
}
