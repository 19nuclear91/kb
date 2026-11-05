package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.eval.input.InputReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayInputStream;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorTest {
    @TempDir
    Path root;
    final AtomicLong clock = new AtomicLong();
    final Fixtures.FakeTransport transport = new Fixtures.FakeTransport();
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    int run(Collector collector) {
        return CollectionCli.execute(Fixtures.args(root), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), collector);
    }

    Collector collector() {
        return Fixtures.collector(transport, clock, new OutputPublisher());
    }

    @Test
    void successfulOutputIsDirectlyReadableByUnmodifiedEvaluator() throws IOException {
        Fixtures.files(root, "b", "a");
        transport.add(Fixtures.me(7)).add(Fixtures.response(200, """
                {"code":200,"data":[
                 {"docId":9223372036854775807,"versionId":9007199254740993,"chunkId":3,"title":"private-title","content":"private-body","score":0},
                 {"docId":9223372036854775807,"versionId":9007199254740993,"chunkId":4,"score":100},
                 {"docId":1,"versionId":2,"chunkId":5}]}
                """)).add(Fixtures.empty());
        assertThat(run(collector())).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("COLLECT_COMPLETE" + System.lineSeparator());
        assertThat(stderr.size()).isZero();
        Path output = root.resolve("output");
        try (var files = Files.list(output)) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("run.json", "results.jsonl", "collection.json");
        }
        var inputs = new InputReader().read(root.resolve("dataset.json").toString(), List.of(output.resolve("run.json").toString()), 200);
        var run = inputs.runs().getFirst();
        assertThat(run.sourceType().name()).isEqualTo("RECORDED");
        assertThat(run.cases().get("a").hits()).isEmpty();
        assertThat(run.cases().get("b").hits()).extracting(hit -> hit.chunkId()).containsExactly(3L, 4L, 5L);
        assertThat(run.cases().get("b").hits().getFirst().docId()).isEqualTo(Long.MAX_VALUE);
        List<String> rows = Files.readAllLines(output.resolve("results.jsonl"));
        assertThat(rows.stream().map(Fixtures::json).map(row -> row.path("caseId").textValue())).containsExactly("b", "a");
        JsonNode metadata = Fixtures.read(output.resolve("collection.json"));
        assertThat(metadata.path("datasetSha256").textValue()).isEqualTo(inputs.dataset().manifestSha256());
        assertThat(metadata.path("labelsSha256").textValue()).isEqualTo(inputs.dataset().labelsSha256());
        assertThat(metadata.path("resultsSha256").textValue()).isEqualTo(run.resultsSha256());
        assertThat(metadata.path("casesSha256").textValue()).isEqualTo(JsonSupport.sha256(Files.readAllBytes(root.resolve("cases.jsonl"))));
        assertThat(metadata.path("resultSemantics").textValue()).isEqualTo("FINAL_RESPONSE");
        assertThat(metadata.path("degradationStatus").textValue()).isEqualTo("UNOBSERVED");
        assertThat(metadata.path("snapshotVerification").textValue()).isEqualTo("DECLARED_ONLY");
        assertThat(metadata.path("startedAt").textValue()).isEqualTo("2026-11-05T06:00:00Z");
        assertThat(metadata.path("endedAt").textValue()).isEqualTo("2026-11-05T06:00:00Z");
        assertThat(metadata.path("caseCount").intValue()).isEqualTo(2);
        for (String file : List.of("run.json", "results.jsonl", "collection.json")) {
            assertThat(Files.readString(output.resolve(file))).doesNotContain(Fixtures.SECRET, "example.invalid", "private-body",
                    "private-title", "tokenEnv", "username", "roles", "fileType", "\"query\":", "score");
        }
        assertThat(transport.maximum.get()).isEqualTo(1);
        assertThat(transport.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unknown", "duplicate", "identity", "request", "lateInvalid"})
    void allLocalCaseErrorsPreventClientCreation(String kind) throws IOException {
        Fixtures.files(root, "a", "b");
        String valid = Fixtures.item("a", "staff");
        String invalid = switch (kind) {
            case "missing" -> valid;
            case "unknown" -> valid + "\n" + Fixtures.item("c", "staff");
            case "duplicate" -> valid + "\n" + valid;
            case "identity" -> valid + "\n" + Fixtures.item("b", "missing");
            case "request" -> valid.replace("\"request\"", "\"userId\"");
            default -> valid + "\n" + Fixtures.item("b", "staff").replace("\"query\"", "\"vector\"");
        };
        Files.writeString(root.resolve("cases.jsonl"), invalid);
        AtomicInteger clients = new AtomicInteger();
        Collector collector = new Collector(name -> Fixtures.SECRET, config -> {
            clients.incrementAndGet();
            return transport;
        }, clock::get, Fixtures.UTC, new OutputPublisher());
        assertThat(run(collector)).isEqualTo(2);
        assertThat(clients.get()).isZero();
        assertThat(transport.requests).isEmpty();
        assertThat(root.resolve("output")).doesNotExist();
    }

    @Test
    void allTokensAreFrozenAndAllIdentitiesCheckedBeforeSearch() throws IOException {
        Fixtures.files(root, "a", "b", "c");
        ObjectNode config = Fixtures.config();
        ((ObjectNode) config.path("identities")).set("other", Fixtures.json("{\"tokenEnv\":\"SECOND\",\"expectedUserId\":8}"));
        ((ObjectNode) config.path("identities")).set("unused", Fixtures.json("{\"tokenEnv\":\"UNUSED\",\"expectedUserId\":9}"));
        Files.write(root.resolve("config.json"), JsonSupport.encode(config));
        Files.writeString(root.resolve("cases.jsonl"), Fixtures.item("a", "staff") + "\n" + Fixtures.item("b", "other") + "\n" + Fixtures.item("c", "staff"));
        AtomicInteger envCalls = new AtomicInteger();
        transport.add((request, deadline) -> {
            assertThat(envCalls.get()).isEqualTo(2);
            return Fixtures.me(7);
        }).add(Fixtures.me(8)).add(Fixtures.empty()).add(Fixtures.empty()).add(Fixtures.empty());
        Collector collector = new Collector(name -> {
            assertThat(name).isNotEqualTo("UNUSED");
            return name + envCalls.incrementAndGet();
        }, ignored -> transport, clock::get, Fixtures.UTC, new OutputPublisher());
        assertThat(run(collector)).isZero();
        assertThat(envCalls.get()).isEqualTo(2);
        assertThat(transport.requests).extracting(Transport.Request::method).containsExactly("GET", "GET", "POST", "POST", "POST");
        assertThat(transport.requests).extracting(request -> request.headers().get("satoken"))
                .containsExactly("TOKEN1", "SECOND2", "TOKEN1", "SECOND2", "TOKEN1");
        assertThat(transport.maximum.get()).isEqualTo(1);
    }

    @Test
    void secondIdentityFailurePreventsEverySearchAndCleansStaging() throws IOException {
        Fixtures.files(root, "a", "b");
        ObjectNode config = Fixtures.config();
        ((ObjectNode) config.path("identities")).set("other", Fixtures.json("{\"tokenEnv\":\"SECOND\",\"expectedUserId\":8}"));
        Files.write(root.resolve("config.json"), JsonSupport.encode(config));
        Files.writeString(root.resolve("cases.jsonl"), Fixtures.item("a", "staff") + "\n" + Fixtures.item("b", "other"));
        transport.add(Fixtures.me(7)).add(Fixtures.me(99));
        assertThat(run(collector())).isEqualTo(3);
        assertThat(transport.requests).extracting(Transport.Request::method).containsExactly("GET", "GET");
        noPublishedOrStaging();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "line\nbreak", "非法"})
    void invalidCredentialsPreventNetwork(String token) throws IOException {
        Fixtures.files(root, "a");
        Collector collector = new Collector(name -> token, config -> {
            throw new AssertionError("凭据错误不得创建客户端");
        }, clock::get, Fixtures.UTC, new OutputPublisher());
        assertThat(run(collector)).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("CREDENTIAL").doesNotContain("line\nbreak", "非法");
        noPublishedOrStaging();
    }

    @Test
    void missingCredentialPreventsNetwork() throws IOException {
        Fixtures.files(root, "a");
        Collector collector = new Collector(name -> null, ignored -> transport, clock::get, Fixtures.UTC, new OutputPublisher());
        assertThat(run(collector)).isEqualTo(2);
        assertThat(transport.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 503})
    void firstFailedSearchStopsBatchAndNeverPublishesPartialRun(int status) throws IOException {
        Fixtures.files(root, "a", "b", "c");
        transport.add(Fixtures.me(7)).add(Fixtures.empty()).add(Fixtures.response(status, Fixtures.SECRET)).add(Fixtures.empty());
        assertThat(run(collector())).isEqualTo(3);
        assertThat(transport.requests).hasSize(3);
        assertThat(transport.steps).hasSize(1);
        assertThat(transport.maximum.get()).isEqualTo(1);
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).doesNotContain(Fixtures.SECRET);
        noPublishedOrStaging();
    }

    @Test
    void latencyIncludesBodyButExcludesPreflightAndWriting() throws IOException {
        Fixtures.files(root, "a");
        transport.add((request, deadline) -> {
            clock.addAndGet(9_000_000_000L);
            return Fixtures.me(7);
        }).add((request, deadline) -> {
            clock.addAndGet(2_000_000L);
            ByteArrayInputStream body = new ByteArrayInputStream(Fixtures.bytes("{\"code\":200,\"data\":[]}")) {
                @Override
                public synchronized int read(byte[] bytes, int offset, int length) {
                    clock.addAndGet(3_000_000L);
                    return super.read(bytes, offset, length);
                }
            };
            return new Transport.Response(200, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true), body);
        });
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void write(Path file, byte[] bytes) throws IOException {
                clock.addAndGet(100_000_000L);
                super.write(file, bytes);
            }
        };
        assertThat(run(Fixtures.collector(transport, clock, publisher))).isZero();
        JsonNode result = Fixtures.json(Files.readString(root.resolve("output/results.jsonl")));
        assertThat(result.path("latencyMs").doubleValue()).isEqualTo(8.0);
    }

    @Test
    void frozenInputHashesRemainValidIfFilesChangeAfterPreflight() throws IOException {
        Fixtures.files(root, "a");
        byte[] originalLabels = Files.readAllBytes(root.resolve("labels.jsonl"));
        String labelsHash = JsonSupport.sha256(originalLabels);
        transport.add((request, deadline) -> {
            Files.writeString(root.resolve("labels.jsonl"), "modified");
            Files.writeString(root.resolve("cases.jsonl"), "modified");
            return Fixtures.me(7);
        }).add(Fixtures.empty());
        assertThat(run(collector())).isZero();
        assertThat(Fixtures.read(root.resolve("output/run.json")).path("labelsSha256").textValue()).isEqualTo(labelsHash);
        assertThat(JsonSupport.parse(transport.requests.getLast().body(), "test", 0).path("query").textValue()).isEqualTo(Fixtures.SECRET);
        Files.write(root.resolve("labels.jsonl"), originalLabels);
        new InputReader().read(root.resolve("dataset.json").toString(), List.of(root.resolve("output/run.json").toString()), 20);
    }

    @Test
    void bindingDigestTracksScenarioMeaningRatherThanRunOrCredentials() {
        ObjectNode config = Fixtures.config();
        String cases = Fixtures.item("a", "staff") + "\n" + Fixtures.item("b", "staff");
        String original = binding(cases, config);
        config.put("runId", "candidate").put("endpointId", "another").put("baseUrl", "https://another.invalid")
                .put("configurationId", "changed").put("candidates", 1).put("topK", 2);
        ((ObjectNode) config.path("identities").path("staff")).put("tokenEnv", "ROTATED_TOKEN");
        assertThat(binding(cases, config)).isEqualTo(original);
        assertThat(binding(Fixtures.item("b", "staff") + "\n" + Fixtures.item("a", "staff"), config)).isEqualTo(original);
        assertThat(binding(cases.replace(Fixtures.SECRET, "changed-query"), config)).isNotEqualTo(original);
        assertThat(binding(cases.replace("\"query\":", "\"fileType\":\"PDF\",\"query\":"), config)).isNotEqualTo(original);
        ((ObjectNode) config.path("identities").path("staff")).put("expectedUserId", 9007199254740993L);
        String changed = binding(cases, config);
        assertThat(changed).isNotEqualTo(original);
        ((ObjectNode) config.path("identities").path("staff")).put("expectedUserId", 9007199254740992L);
        assertThat(binding(cases, config)).isNotEqualTo(changed);
        String left = cases.replace("\"query\":", "\"tagIds\":[1,2],\"query\":");
        String right = cases.replace("\"query\":", "\"tagIds\":[2,1],\"query\":");
        assertThat(binding(left, config)).isNotEqualTo(binding(right, config));
        assertThat(binding(left, config)).isEqualTo(binding(left.replace("\"tagIds\":[1,2],\"query\":\"" + Fixtures.SECRET + "\"",
                "\"query\":\"" + Fixtures.SECRET + "\",\"tagIds\":[1,2]"), config));
    }

    private String binding(String cases, ObjectNode node) {
        Inputs.Config config = Inputs.config(node);
        return Inputs.binding(Inputs.cases(Fixtures.bytes(cases), config, Set.of("a", "b")), config);
    }

    private void noPublishedOrStaging() throws IOException {
        assertThat(root.resolve("output")).doesNotExist();
        try (var paths = Files.list(root)) {
            assertThat(paths.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".kb-eval-collector-"));
        }
    }
}
