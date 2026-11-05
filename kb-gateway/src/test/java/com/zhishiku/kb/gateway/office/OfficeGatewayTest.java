package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeProtocol;
import com.zhishiku.kb.common.office.OfficeServiceSignature;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(OutputCaptureExtension.class)
class OfficeGatewayTest {
    private static final String ID = "a".repeat(32);
    private static final String KEY = "kb-" + "b".repeat(64);
    private static final String COOKIE = "c".repeat(43);
    private final ObjectMapper json = new ObjectMapper();

    private OfficeGatewaySettings settings() {
        OfficeGatewaySettings config = new OfficeGatewaySettings();
        config.setEnabled(true);
        config.setApplicationOrigin("https://kb.example.test");
        config.setPreviewOrigin("https://preview.example.test");
        config.setAppBaseUrl("http://application:8081");
        config.setUpstreamUrl("http://documentserver");
        config.setGatewaySecret("g".repeat(32));
        config.validate();
        return config;
    }

    private JsonNode grant() {
        return json.valueToTree(Map.of("documentKey", KEY, "configurationToken", "signed-config",
                "sourceUrl", "http://application:8081/internal/office-preview/sessions/" + ID + "/source",
                "expiresAt", System.currentTimeMillis() + 45000));
    }

    private MockServerWebExchange request(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("https://preview.example.test/s/" + ID + path)
                .header("Host", "preview.example.test").header("satoken", "must-not-forward")
                .cookie(new HttpCookie(OfficeProtocol.COOKIE_NAME, COOKIE)));
    }

    @Test
    void bootstrapRequiresExactOriginAndPathScopedCookieWithoutApplicationCredential() {
        OfficeAuthorizationClient auth = mock(OfficeAuthorizationClient.class);
        when(auth.call(anyString(), org.mockito.ArgumentMatchers.anyMap())).thenReturn(Mono.just(json.valueToTree(
                Map.of("cookie", COOKIE, "expiresAt", System.currentTimeMillis() + 1800000))));
        OfficeViewerFilter filter = new OfficeViewerFilter(settings(), auth, json);
        for (String origin : new String[]{"null", "https://evil.test", ""}) {
            var bad = MockServerWebExchange.from(MockServerHttpRequest.post("https://preview.example.test/s/" + ID + "/bootstrap")
                    .header("Host", "preview.example.test").header("Origin", origin).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("launchToken=" + "p".repeat(43)));
            filter.filter(bad, ignored -> Mono.error(new AssertionError())).block();
            assertThat(bad.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("https://preview.example.test/s/" + ID + "/bootstrap")
                .header("Host", "preview.example.test").header("Origin", "https://kb.example.test").header("satoken", "secret")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body("launchToken=" + "p".repeat(43)));
        filter.filter(exchange, ignored -> Mono.error(new AssertionError())).block();
        var cookie = exchange.getResponse().getCookies().getFirst(OfficeProtocol.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isSecure() && cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getPath()).isEqualTo("/s/" + ID + "/");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
        assertThat(exchange.getResponse().getHeaders().toString()).doesNotContain("secret", "launchToken");
        assertThat(exchange.getResponse().getHeaders().getFirst("Content-Security-Policy")).contains("object-src 'none'", "frame-ancestors https://kb.example.test");
    }

    @Test
    void clientSignsOnlyInternalRequestAndNormalizesErrors(CapturedOutput output) {
        OfficeGatewaySettings config = settings();
        WebClient http = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("http://application:8081/internal/office-preview/authorize");
            assertThat(request.headers().getFirst(OfficeServiceSignature.HEADER)).isNotBlank();
            assertThat(request.headers()).doesNotContainKeys("satoken", "Cookie");
            return Mono.just(ClientResponse.create(HttpStatus.BAD_GATEWAY).body("secret upstream details").build());
        }).build();
        var client = new OfficeAuthorizationClient(config, json, http);
        StepVerifier.create(client.authorize(ID, COOKIE)).expectErrorSatisfies(error -> {
            assertThat(error).isInstanceOf(OfficeProxyFailure.class).hasMessage("Office 预览请求未完成");
        }).verify();
        config.setEnabled(false);
        StepVerifier.create(client.authorize(ID, COOKIE)).expectError(OfficeProxyFailure.class).verify();
        assertThat(output.getAll()).doesNotContain(COOKIE, "secret upstream details", "must-not-forward");
    }

    @Test
    void everyResourceReauthorizesAndNeverForwardsCredentialsOrRedirects() {
        OfficeAuthorizationClient auth = mock(OfficeAuthorizationClient.class);
        AtomicInteger reads = new AtomicInteger();
        when(auth.authorize(ID, COOKIE)).thenAnswer(invocation -> reads.incrementAndGet() < 3 ? Mono.just(grant()) : Mono.error(new OfficeProxyFailure(404)));
        AtomicInteger upstream = new AtomicInteger();
        var metrics = new SimpleMeterRegistry();
        var http = WebClient.builder().exchangeFunction(request -> {
            upstream.incrementAndGet();
            assertThat(request.headers()).doesNotContainKeys("satoken", "Cookie", "Authorization", "baggage");
            assertThat(request.headers().getFirst("X-Forwarded-Host")).isEqualTo("preview.example.test/s/" + ID);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/octet-stream")
                    .header("Set-Cookie", "vendor=secret").body("content").build());
        }).build();
        var proxy = new OfficeResourceProxy(settings(), auth, json, metrics, http, null, null);
        String path = "/cache/files/data/" + KEY + "/Editor.bin/Editor.bin";
        for (int i = 0; i < 2; i++) {
            var exchange = request(path);
            proxy.proxy(exchange).block();
            assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("content");
            assertThat(exchange.getResponse().getHeaders()).doesNotContainKey("Set-Cookie");
        }
        StepVerifier.create(proxy.proxy(request(path))).expectError(OfficeProxyFailure.class).verify();
        assertThat(upstream.get()).isEqualTo(2);
        assertThat(metrics.getMeters().getFirst().getId().getTags().toString()).doesNotContain(ID, KEY);
        when(auth.authorize(ID, COOKIE)).thenReturn(Mono.just(grant()));
        for (String denied : new String[]{"/downloadas/" + KEY, "/converter", "/cache/files/data/other/Editor.bin/Editor.bin", "/sdkjs/%252e%252e/api"}) {
            StepVerifier.create(proxy.proxy(request(denied))).expectError(OfficeProxyFailure.class).verify();
        }
        assertThat(upstream.get()).isEqualTo(2);
        var redirectHttp = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.FOUND)
                .header("Location", "https://escape.test/file").build())).build();
        var redirectProxy = new OfficeResourceProxy(settings(), auth, json, metrics, redirectHttp, null, null);
        StepVerifier.create(redirectProxy.proxy(request(path))).expectError(OfficeProxyFailure.class).verify();
    }

    @Test
    void bidirectionalMessageStreamsStopOnRevocationAndRejectWritesAndLargeFrames() {
        OfficeAuthorizationClient auth = mock(OfficeAuthorizationClient.class);
        var config = settings();
        var proxy = new OfficeResourceProxy(config, auth, json, new SimpleMeterRegistry(), null, null, null);
        for (boolean browser : new boolean[]{true, false}) {
            AtomicInteger count = new AtomicInteger();
            when(auth.authorize(ID, COOKIE)).thenAnswer(invocation -> count.incrementAndGet() == 1 ? Mono.just(grant()) : Mono.error(new OfficeProxyFailure(401)));
            WebSocketSession source = mock(WebSocketSession.class), target = mock(WebSocketSession.class);
            String frame = browser ? "42[\"message\",{\"type\":\"extendSession\"}]" : "42[\"message\",{\"type\":\"session\"}]";
            when(source.receive()).thenReturn(Flux.just(message(frame), message(frame)));
            when(target.textMessage(anyString())).thenAnswer(invocation -> message(invocation.getArgument(0)));
            StepVerifier.create(proxy.messages(source, target, browser, ID, COOKIE)).expectNextCount(1).expectError(OfficeProxyFailure.class).verify();
            assertThat(count.get()).isEqualTo(2);
        }
        var wire = new OfficeWireProtocol(json, config);
        assertThat(wire.query("_dc=0&lang=zh-CN&customer=ONLYOFFICE&type=desktop&frameEditorId=viewer&mode=view&isForm=false&parentOrigin=https://preview.example.test&fileType=docx", false, KEY))
                .containsEntry("parentOrigin", "https://preview.example.test");
        assertThatThrownBy(() -> wire.query("parentOrigin=https://evil.test", false, KEY)).isInstanceOf(OfficeProxyFailure.class);
        for (String frame : new String[]{"42[\"message\",{\"type\":\"saveChanges\"}]", "42[\"message\",{\"type\":\"openDocument\",\"message\":{\"c\":\"open\",\"id\":\"other\"}}]", "x".repeat(config.getMaxMessageBytes() + 1)}) {
            assertThatThrownBy(() -> wire.frame(frame, true, grant(), ID)).isInstanceOf(OfficeProxyFailure.class);
        }
        assertThatThrownBy(() -> wire.query("EIO=4&transport=polling&url=http", true, KEY)).isInstanceOf(OfficeProxyFailure.class);
        String output = wire.frame("42[\"message\",{\"type\":\"documentOpen\",\"data\":{\"origin.docx\":\"http://private/origin.docx\",\"Editor.bin\":\"https://preview.example.test/s/" + ID + "/cache/files/data/" + KEY + "/Editor.bin/Editor.bin\"}}]", false, grant(), ID);
        assertThat(output).contains("Editor.bin").doesNotContain("origin.docx", "http://private");
    }

    @Test
    void bindingsRejectCrossSessionIdsAndHaveFiniteCapacity() {
        OfficeConnections connections = new OfficeConnections(1, Clock.systemUTC());
        connections.enter(ID, null, System.currentTimeMillis() + 10000, false);
        connections.opened(ID, "engineA");
        assertThatThrownBy(() -> connections.enter(ID, "engineB", Long.MAX_VALUE, false)).isInstanceOf(OfficeProxyFailure.class);
        assertThatThrownBy(() -> connections.enter("b".repeat(32), null, Long.MAX_VALUE, false)).isInstanceOf(OfficeProxyFailure.class);
        connections.remove(ID);
        connections.enter("b".repeat(32), null, Long.MAX_VALUE, true);
        var config = settings();
        config.setMaxConnections(Integer.MAX_VALUE);
        assertThatThrownBy(config::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void authorizationTimeoutAndResourceBoundsFailClosedAndCancellationReleasesCapacity() {
        var config = settings();
        var never = WebClient.builder().exchangeFunction(request -> Mono.never()).build();
        var internal = new OfficeAuthorizationClient(config, json, never);
        StepVerifier.withVirtualTime(() -> internal.authorize(ID, COOKIE)).thenAwait(Duration.ofSeconds(4))
                .expectError(OfficeProxyFailure.class).verify();
        var auth = mock(OfficeAuthorizationClient.class);
        when(auth.authorize(ID, COOKIE)).thenReturn(Mono.just(grant()));
        config.setMaxConnections(1);
        var proxy = new OfficeResourceProxy(config, auth, json, new SimpleMeterRegistry(), never, null, null);
        var first = proxy.proxy(request("/sdkjs/a.js")).subscribe();
        var second = proxy.proxy(request("/sdkjs/b.js")).subscribe();
        var third = proxy.proxy(request("/sdkjs/c.js")).subscribe();
        var fourth = proxy.proxy(request("/sdkjs/d.js")).subscribe();
        StepVerifier.create(proxy.proxy(request("/sdkjs/e.js"))).expectErrorSatisfies(error ->
                assertThat(((OfficeProxyFailure) error).status()).isEqualTo(429)).verify();
        first.dispose();
        StepVerifier.create(proxy.proxy(request("/sdkjs/f.js"))).thenCancel().verify();
        second.dispose();
        third.dispose();
        fourth.dispose();
        StepVerifier.withVirtualTime(() -> proxy.proxy(request("/sdkjs/slow.js"))).thenAwait(Duration.ofSeconds(31))
                .expectError(java.util.concurrent.TimeoutException.class).verify();
        config.setMaxResourceBytes(2);
        var large = WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[2]),
                        DefaultDataBufferFactory.sharedInstance.wrap(new byte[2]))).build())).build();
        var bounded = new OfficeResourceProxy(config, auth, json, new SimpleMeterRegistry(), large, null, null);
        StepVerifier.create(bounded.proxy(request("/sdkjs/large.js"))).expectError(OfficeProxyFailure.class).verify();
    }

    @Test
    void readonlyInitializationAcceptsLicenseBeforeAuthenticationAndDocumentOpen() {
        var config = settings();
        var wire = new OfficeWireProtocol(json, config);
        String engineOpen = "0{\"sid\":\"engineA\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":20000,\"maxPayload\":1000000}";
        String authData = "{\"type\":\"auth\",\"docid\":\"" + KEY + "\",\"mode\":\"view\",\"jwtOpen\":\"signed-config\","
                + "\"permissions\":{\"edit\":false,\"download\":false,\"print\":false},\"openCmd\":{\"c\":\"open\",\"id\":\"" + KEY + "\"}}";
        String connect = "40{\"token\":\"signed-config\",\"data\":" + authData + "}";
        assertThat(wire.frame(engineOpen, false, grant(), ID)).isEqualTo(engineOpen);
        assertThat(wire.frame(connect, true, grant(), ID)).isEqualTo(connect);

        // 固定版本的顺序：连接、许可、客户端认证、认证结果、打开结果；全程使用内存消息。
        String connected = "40{\"sid\":\"socketA\"}";
        String license = "42[\"message\",{\"type\":\"license\",\"license\":{\"type\":3,\"light\":false,\"mode\":0,"
                + "\"rights\":1,\"buildVersion\":\"10.0.0\",\"buildNumber\":176,\"protectionSupport\":false},"
                + "\"aiPluginSettings\":{},\"aiPluginSettingsAllowBrowserProxy\":false}]";
        String authenticated = "42[\"message\",{\"type\":\"auth\",\"result\":1,\"jwt\":\"supplier-session-token\"}]";
        String editorUrl = "https://preview.example.test/s/" + ID + "/cache/files/data/" + KEY + "/Editor.bin/Editor.bin";
        String opened = "42[\"message\",{\"type\":\"documentOpen\",\"data\":{\"type\":\"open\",\"status\":\"ok\","
                + "\"data\":{\"Editor.bin\":\"" + editorUrl + "\",\"origin.docx\":\"http://private/origin.docx\"}}}]";
        var authorization = mock(OfficeAuthorizationClient.class);
        when(authorization.authorize(ID, COOKIE)).thenReturn(Mono.just(grant()));
        var proxy = new OfficeResourceProxy(config, authorization, json, new SimpleMeterRegistry(), null, null, null);
        var vendor = mock(WebSocketSession.class);
        var browser = mock(WebSocketSession.class);
        when(vendor.receive()).thenReturn(Flux.just(message(connected), message(license), message(authenticated), message(opened)));
        when(browser.textMessage(anyString())).thenAnswer(invocation -> message(invocation.getArgument(0)));
        StepVerifier.create(proxy.messages(vendor, browser, false, ID, COOKIE))
                .assertNext(frame -> assertThat(frame.getPayloadAsText()).isEqualTo(connected))
                .assertNext(frame -> {
                    assertThat(frame.getPayloadAsText()).isEqualTo(license);
                    String auth = "42[\"message\"," + authData + "]";
                    assertThat(wire.frame(auth, true, grant(), ID)).isEqualTo(auth);
                })
                .assertNext(frame -> assertThat(frame.getPayloadAsText()).isEqualTo(authenticated))
                .assertNext(frame -> assertThat(frame.getPayloadAsText()).contains(editorUrl).doesNotContain("origin.docx", "http://private"))
                .verifyComplete();
        verify(authorization, times(4)).authorize(ID, COOKIE);
    }

    @Test
    void licenseCannotBeSpoofedByBrowserOrExpandUnknownMessageTypes() {
        var wire = new OfficeWireProtocol(json, settings());
        String license = "42[\"message\",{\"type\":\"license\",\"license\":{\"type\":3}}]";
        assertThatThrownBy(() -> wire.frame(license, true, grant(), ID)).isInstanceOf(OfficeProxyFailure.class);
        for (String invalid : new String[]{"{}", "{\"type\":\"3\"}", "null", "[]"}) {
            String frame = "42[\"message\",{\"type\":\"license\",\"license\":" + invalid + "}]";
            assertThatThrownBy(() -> wire.frame(frame, false, grant(), ID)).isInstanceOf(OfficeProxyFailure.class);
        }
        assertThatThrownBy(() -> wire.frame(license.replace("\"license\",", "\"unknown\","), false, grant(), ID))
                .isInstanceOf(OfficeProxyFailure.class);
    }

    @Test
    void configurationTokenAndKeyCannotBeSubstitutedDuringHandshake() {
        var wire = new OfficeWireProtocol(json, settings());
        String data = "{\"type\":\"auth\",\"docid\":\"" + KEY + "\",\"mode\":\"view\",\"openCmd\":{\"c\":\"open\",\"id\":\"" + KEY + "\"}}";
        String packet = "40{\"token\":\"signed-config\",\"data\":" + data + "}";
        assertThat(wire.frame(packet, true, grant(), ID)).isEqualTo(packet);
        for (String invalid : new String[]{packet.replace("signed-config", "tampered"), packet.replace(KEY, "kb-" + "c".repeat(64)),
                packet.replace("\"view\"", "\"edit\""), packet.replace("\"c\":\"open\"", "\"c\":\"setpassword\"")}) {
            assertThatThrownBy(() -> wire.frame(invalid, true, grant(), ID)).isInstanceOf(OfficeProxyFailure.class);
        }
        assertThatThrownBy(() -> wire.frame("42[\"message\",{\"type\":\"error\",\"description\":\"secret raw error\"}]", false, grant(), ID))
                .isInstanceOf(OfficeProxyFailure.class).hasMessageNotContaining("secret raw error");
    }

    @Test
    @SuppressWarnings("unchecked")
    void idleRevocationClosesBothMockConnectionsWithoutOpeningAnySocket() {
        var auth = mock(OfficeAuthorizationClient.class);
        AtomicInteger calls = new AtomicInteger();
        when(auth.authorize(ID, COOKIE)).thenAnswer(invocation -> calls.incrementAndGet() == 1
                ? Mono.just(grant()) : Mono.error(new OfficeProxyFailure(401)));
        var browser = mock(WebSocketSession.class);
        var vendor = mock(WebSocketSession.class);
        for (var connection : new WebSocketSession[]{browser, vendor}) {
            when(connection.receive()).thenReturn(Flux.never());
            when(connection.send(any(Publisher.class))).thenAnswer(invocation -> Flux.from((Publisher<WebSocketMessage>) invocation.getArgument(0)).then());
            when(connection.close(any(CloseStatus.class))).thenReturn(Mono.empty());
        }
        var sockets = mock(WebSocketClient.class);
        when(sockets.execute(any(URI.class), any(HttpHeaders.class), any(WebSocketHandler.class)))
                .thenAnswer(invocation -> ((WebSocketHandler) invocation.getArgument(2)).handle(vendor));
        WebSocketService upgrade = (exchange, handler) -> handler.handle(browser);
        var proxy = new OfficeResourceProxy(settings(), auth, json, new SimpleMeterRegistry(), null, sockets, upgrade);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("https://preview.example.test/s/" + ID + "/doc/" + KEY + "/c/?EIO=4&transport=websocket")
                .header("Host", "preview.example.test").header("Origin", "https://preview.example.test").header("Upgrade", "websocket")
                .cookie(new HttpCookie(OfficeProtocol.COOKIE_NAME, COOKIE)));
        StepVerifier.withVirtualTime(() -> proxy.proxy(exchange)).thenAwait(Duration.ofSeconds(11)).verifyComplete();
        verify(browser).close(CloseStatus.POLICY_VIOLATION);
        verify(vendor).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void disabledSettingsDoNotBreakOrdinaryRoutesAndCommittedErrorsDoNotEscape(CapturedOutput output) {
        var config = settings();
        config.setEnabled(false);
        config.setPreviewOrigin("invalid origin");
        var filter = new OfficeViewerFilter(config, mock(OfficeAuthorizationClient.class), json);
        AtomicInteger ordinary = new AtomicInteger();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/documents/1")), exchange -> {
            ordinary.incrementAndGet();
            return Mono.empty();
        }).block();
        assertThat(ordinary.get()).isEqualTo(1);
        var committed = request("/sdkjs/file.js?md5=private-signature");
        committed.getResponse().writeWith(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[]{1}))).block();
        OfficePreviewExchange.error(committed, new IllegalStateException("private-signature")).block();
        assertThat(output.getAll()).doesNotContain("private-signature");
    }

    private WebSocketMessage message(String text) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT, DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }
}
