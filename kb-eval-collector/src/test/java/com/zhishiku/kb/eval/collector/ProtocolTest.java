package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolTest {
    final AtomicLong clock = new AtomicLong();
    final Fixtures.FakeTransport transport = new Fixtures.FakeTransport();

    Inputs.Config config() {
        return Inputs.config(Fixtures.config());
    }

    Inputs.Case item(boolean filtered) {
        String value = "{\"caseId\":\"a\",\"identity\":\"staff\",\"request\":{\"query\":\"x\""
                + (filtered ? ",\"fileType\":\"PDF\"" : "") + "}}";
        return Inputs.cases(Fixtures.bytes(value), config(), Set.of("a")).getFirst();
    }

    Protocol.SearchResult search() {
        return new Protocol(transport, config(), clock::get).search(Fixtures.SECRET, item(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.invalid", "https://user:secret@example.invalid", "https://example.invalid?q=x",
            "https://example.invalid#fragment", "https://example.invalid/a/../b", "https://example.invalid/%2e%2e/",
            "https://example.invalid/%252e/", "https://example.invalid/a%2fb", "https://example.invalid:0",
            "https://example.invalid:65536", "http://127.0.0.2", "http://localhost.evil.invalid", "ftp://localhost"})
    void invalidUrlsFailLocally(String value) {
        assertThatThrownBy(() -> Endpoint.validate(value, true)).isInstanceOf(CollectionException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void preservesPrefixAndRequiresExplicitLoopbackAllowance() {
        assertThat(Endpoint.api(Endpoint.validate("https://example.invalid/kb/", false), "/api/search"))
                .isEqualTo(URI.create("https://example.invalid/kb/api/search"));
        for (String value : List.of("http://localhost", "http://127.0.0.1:8080", "http://[::1]:8080")) {
            assertThat(Endpoint.validate(value, true)).isEqualTo(URI.create(value));
            assertThatThrownBy(() -> Endpoint.validate(value, false)).isInstanceOf(CollectionException.class);
        }
    }

    @Test
    void clientIsIsolatedAndDoesNotFollowRedirects() {
        HttpClient client = JdkTransport.createClient(config());
        try {
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
            assertThat(client.cookieHandler()).isEmpty();
            assertThat(client.authenticator()).isEmpty();
            assertThat(client.connectTimeout()).contains(Duration.ofMillis(5000));
            assertThat(client.proxy().orElseThrow().select(URI.create("https://example.invalid"))).containsExactly(Proxy.NO_PROXY);
            assertThat(System.getProperty("jdk.httpclient.disableRetryConnect")).isEqualTo("true");
            assertThat(System.getProperty("jdk.httpclient.redirects.retrylimit")).isEqualTo("1");
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void sendsOnlyNecessaryHeadersAndExactQuantityFields() {
        transport.add(Fixtures.me(7)).add(Fixtures.empty());
        Protocol protocol = new Protocol(transport, config(), clock::get);
        protocol.identity("token-one", 7);
        protocol.search("token-two", item(false));
        assertThat(transport.requests).extracting(Transport.Request::method).containsExactly("GET", "POST");
        assertThat(transport.requests.getFirst().headers()).containsEntry("satoken", "token-one")
                .containsOnlyKeys("satoken", "Accept", "Accept-Encoding");
        Transport.Request request = transport.requests.getLast();
        assertThat(request.headers()).containsEntry("satoken", "token-two")
                .containsOnlyKeys("satoken", "Accept", "Accept-Encoding", "Content-Type");
        assertThat(request.uri().toString()).isEqualTo("https://example.invalid/kb/api/search");
        assertThat(request.uri().getQuery()).isNull();
        ObjectNode body = (ObjectNode) JsonSupport.parse(request.body(), "test", 0);
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("candidates").intValue()).isEqualTo(50);
        assertThat(body.path("topK").intValue()).isEqualTo(20);
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 301, 302, 307, 308, 401, 403, 429, 503})
    void unsuccessfulStatusNeverRetriesOrRedirects(int status) {
        transport.add(Fixtures.response(status, Fixtures.SECRET,
                Map.of("Location", List.of("https://private.invalid"), "Retry-After", List.of("1"))));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("COLLECT_ERROR HTTP")
                .hasMessageNotContaining(Fixtures.SECRET);
        assertThat(transport.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{\"code\":500,\"message\":\"secret\",\"data\":[]}",
            "{\"code\":200,\"data\":null}", "{\"code\":200,\"data\":{}}", "{\"code\":200.0,\"data\":[]}",
            "{\"code\":200,\"data\":[],\"data\":[]}", "{\"code\":200,\"data\":[]} {}",
            "{\"code\":200,\"data\":[null]}", "{\"code\":200,\"data\":[{\"docId\":1,\"docId\":2}]}"})
    void rejectsDamagedSuccessfulWrappers(String body) {
        transport.add(Fixtures.response(200, body));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("PROTOCOL");
        assertThat(transport.requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "\"1\"", "null", "9223372036854775808"})
    void rejectsAnyInvalidHitIdentity(String id) {
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[{\"docId\":1,\"versionId\":" + id + ",\"chunkId\":3}]}"));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("field=versionId");
    }

    @Test
    void preservesEveryRankAndLongWithoutContentOrScore() {
        transport.add(Fixtures.response(200, """
                {"code":200,"data":[
                 {"docId":9223372036854775807,"versionId":9007199254740993,"chunkId":3,"score":0,"content":"secret","title":"secret"},
                 {"docId":9223372036854775807,"versionId":9007199254740993,"chunkId":4,"score":99},
                 {"docId":2,"versionId":5,"chunkId":6,"score":55}]}
                """));
        Protocol.SearchResult result = search();
        assertThat(result.hits()).containsExactly(new Protocol.Hit(Long.MAX_VALUE, 9007199254740993L, 3),
                new Protocol.Hit(Long.MAX_VALUE, 9007199254740993L, 4), new Protocol.Hit(2, 5, 6));
        assertThat(new String(JsonSupport.encode(result), StandardCharsets.UTF_8))
                .doesNotContain("secret", "score", "content", "title");
    }

    @Test
    void rejectsDuplicateTriplesAndMissingVersions() {
        String hit = "{\"docId\":1,\"versionId\":2,\"chunkId\":3}";
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[" + hit + "," + hit + "]}"));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("hits");
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[{\"docId\":1,\"chunkId\":3}]}"));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("versionId");
    }

    @Test
    void validEmptyArrayAndAbsentInactiveFiltersAreCompatible() {
        transport.add(Fixtures.empty());
        assertThat(search().hits()).isEmpty();
        assertThat(Inputs.request(Fixtures.json("{\"query\":\"x\",\"spaceId\":null,\"categoryId\":null,\"uncategorized\":false,\"tagIds\":[]}"), 1))
                .isFalse();
    }

    @Test
    void filterHeaderMustBeUniqueAndExact() {
        for (List<String> versions : List.of(List.<String>of(), List.of("1", "1"), List.of("1", "2"), List.of("1, 1"), List.of("2"))) {
            transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[]}",
                    Map.of("Content-Type", List.of("application/json"), "X-Search-Filter-Version", versions)));
            assertThatThrownBy(() -> new Protocol(transport, config(), clock::get).search("token", item(true)))
                    .isInstanceOf(CollectionException.class).hasMessageContaining("filterVersion");
        }
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[]}",
                Map.of("Content-Type", List.of("application/json;charset=UTF-8"), "X-Search-Filter-Version", List.of("1"))));
        assertThat(new Protocol(transport, config(), clock::get).search("token", item(true)).hits()).isEmpty();
        assertThat(transport.requests).hasSize(6);
    }

    @Test
    void contentEncodingTypeAndJsonDepthAreBounded() {
        for (Map<String, List<String>> headers : List.of(
                Map.of("Content-Type", List.of("text/html")),
                Map.of("Content-Type", List.of("application/json;charset=gbk")),
                Map.of("Content-Type", List.of("application/json", "application/json")),
                Map.of("Content-Type", List.of("application/json"), "Content-Encoding", List.of("gzip")))) {
            transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[]}", headers));
            assertThatThrownBy(this::search).isInstanceOf(CollectionException.class);
        }
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":[],\"ignored\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class);
    }

    @Test
    void checksIdentityFlagsAndExactId() {
        for (String user : List.of("{\"userId\":8,\"enabled\":true}", "{\"userId\":7,\"enabled\":false}",
                "{\"userId\":\"7\",\"enabled\":true}", "{\"userId\":7}")) {
            transport.add(Fixtures.response(200, "{\"code\":200,\"data\":{\"user\":" + user + ",\"mustChangePassword\":false}}"));
            assertThatThrownBy(() -> new Protocol(transport, config(), clock::get).identity("token", 7))
                    .isInstanceOf(CollectionException.class).hasMessageContaining("IDENTITY");
        }
        transport.add(Fixtures.response(200, "{\"code\":200,\"data\":{\"user\":{\"userId\":7,\"enabled\":true},\"mustChangePassword\":true}}"));
        assertThatThrownBy(() -> new Protocol(transport, config(), clock::get).identity("token", 7))
                .isInstanceOf(CollectionException.class);
        transport.add(Fixtures.me(Long.MAX_VALUE));
        new Protocol(transport, config(), clock::get).identity("token", Long.MAX_VALUE);
    }

    @Test
    void slowContinuousBodyDoesNotResetDeadlineAndClosesStream() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream(Fixtures.bytes("{\"code\":200,\"data\":[]}")) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                clock.addAndGet(5_000_000_000L);
                return super.read(bytes, offset, Math.min(1, length));
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
        transport.add(new Transport.Response(200, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true), body));
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("TIMEOUT");
        assertThat(closed).isTrue();
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void actualBytesAreLimitedEvenWithoutContentLength() {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream body = new ByteArrayInputStream(new byte[100]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        transport.add(new Transport.Response(200, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true), body));
        Inputs.Config config = Inputs.config(Fixtures.config().put("maxResponseBytes", 10));
        assertThatThrownBy(() -> new Protocol(transport, config, clock::get).search("token", item(false)))
                .isInstanceOf(CollectionException.class).hasMessageContaining("bytes");
        assertThat(body.available()).isEqualTo(89);
        assertThat(closed).isTrue();
    }

    @Test
    void deadlineIsCheckedDuringParsing() {
        AtomicLong ticks = new AtomicLong();
        Deadline deadline = new Deadline(() -> ticks.addAndGet(1_000_000), 10);
        assertThatThrownBy(() -> JsonSupport.parse(Fixtures.bytes("{\"data\":[" + "{},".repeat(50) + "{}]}"),
                "response", 0, CollectionException.Code.PROTOCOL, deadline::check))
                .isInstanceOf(CollectionException.class).hasMessageContaining("TIMEOUT");
    }

    @Test
    void disconnectedTransportNeverRetriesOrLeaksCause() {
        transport.add((request, deadline) -> {
            throw new IOException(Fixtures.SECRET);
        });
        assertThatThrownBy(this::search).isInstanceOf(CollectionException.class).hasMessageContaining("TRANSPORT")
                .hasMessageNotContaining(Fixtures.SECRET).hasNoCause();
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void interruptionCancelsBlockingBodyWithoutOpeningAnySocket() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    released.await();
                    return -1;
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }

            @Override
            public void close() {
                closed.set(true);
                released.countDown();
            }
        };
        transport.add(new Transport.Response(200, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true), body));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                search();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        try {
            assertThat(reading.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(CollectionException.class).hasMessageContaining("INTERRUPTED");
            assertThat(closed).isTrue();
        } finally {
            released.countDown();
            caller.interrupt();
        }
    }
}
