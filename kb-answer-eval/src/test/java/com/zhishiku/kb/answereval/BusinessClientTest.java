package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessClientTest {
    @TempDir
    Path root;

    @Test
    void validatesEndpointsAndRetainsPrefixWithoutRelaxingHttps() {
        assertThat(Endpoint.api(Endpoint.validate("https://kb.invalid/prefix/", false), "/api/auth/me").toString())
                .isEqualTo("https://kb.invalid/prefix/api/auth/me");
        assertThat(Endpoint.validate("http://127.0.0.1:8080/prefix", true).getHost()).isEqualTo("127.0.0.1");
        for (String address : List.of("http://kb.invalid", "https://user:secret@kb.invalid", "https://kb.invalid?secret=1",
                "https://kb.invalid/a/../b", "https://kb.invalid/%2f", "https://kb.invalid//api", "file:///input")) {
            assertThatThrownBy(() -> Endpoint.validate(address, true)).isInstanceOf(EvaluationFailure.class)
                    .hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> Endpoint.validate("http://localhost:8080", false)).isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void freezesCredentialOnceAndPreflightsBeforeAnyWrite() throws IOException {
        var batch = new DatasetReader().read(DataFixtures.dataset(root).toString());
        var config = config(root);
        AtomicInteger reads = new AtomicInteger();
        var credentials = config.credentials(batch, name -> reads.incrementAndGet() == 1 ? "fixed-token" : "changed-token");
        List<Transport.Request> requests = new ArrayList<>();
        Transport stub = (request, deadline) -> {
            requests.add(request);
            if ("GET".equals(request.method())) {
                return response(200, Map.of("code", 200, "data", Map.of("user",
                        Map.of("userId", Long.MAX_VALUE, "enabled", true), "mustChangePassword", false)));
            }
            var conversation = new java.util.LinkedHashMap<String, Object>();
            conversation.put("id", Long.MAX_VALUE);
            conversation.put("lastTurnSeq", 0);
            conversation.put("activeTurnId", null);
            return response(201, Map.of("conversation", conversation, "replayed", false));
        };
        var client = new BusinessClient(stub, config, credentials, System::nanoTime);
        assertThatThrownBy(() -> client.create("employee", DataFixtures.UUID, deadline())).isInstanceOf(EvaluationFailure.class);
        assertThat(requests).isEmpty();
        client.preflight();
        assertThat(client.create("employee", DataFixtures.UUID, deadline())).isEqualTo(Long.MAX_VALUE);
        assertThat(reads).hasValue(1);
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.headers().get("satoken")).isEqualTo("fixed-token");
            assertThat(request.headers().keySet()).doesNotContain("Cookie", "X-Internal-Signature", "X-User-Id");
        });
        assertThat(requests.getFirst().uri().getPath()).isEqualTo("/prefix/api/auth/me");
    }

    @Test
    void wrongUserForcedPasswordDisabledAndRedirectNeverWrite() {
        for (int mode = 0; mode < 4; mode++) {
            int variant = mode;
            List<String> methods = new ArrayList<>();
            Transport stub = (request, deadline) -> {
                methods.add(request.method());
                return response(variant == 3 ? 302 : 200, Map.of("code", 200, "data", Map.of(
                        "user", Map.of("userId", variant == 0 ? 1L : Long.MAX_VALUE, "enabled", variant != 1),
                        "mustChangePassword", variant == 2)));
            };
            var client = new BusinessClient(stub, config(root), new CollectionConfig.Credentials(Map.of("employee", "token")),
                    System::nanoTime);
            assertThatThrownBy(client::preflight).isInstanceOf(EvaluationFailure.class);
            assertThatThrownBy(() -> client.create("employee", DataFixtures.UUID, deadline())).isInstanceOf(EvaluationFailure.class);
            assertThat(methods).containsExactly("GET");
        }
    }

    static CollectionConfig config(Path root) {
        return new CollectionConfig(1, "run", "test", "instance", "artifact-v1", "config-v1", "model",
                "acl-v1", "https://kb.invalid/prefix", false, root.resolve("shared").toString(),
                Map.of("employee", new CollectionConfig.Identity("TEST_TOKEN", Long.MAX_VALUE)), null, null);
    }

    static Transport.Response response(int status, Object json) {
        return new Transport.Response(status, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")),
                (name, value) -> true), new ByteArrayInputStream(StrictJson.bytes(json)));
    }

    private static Deadline deadline() {
        return new Deadline(System::nanoTime, 1000);
    }
}
