package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.zhishiku.kb.eval.input.InputReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputsTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\":1,\"a\":2}", "{} {}", "", "[]", "{\"a\":{\"b\":1,\"b\":2}}"})
    void rejectsMalformedJson(String json) {
        assertThatThrownBy(() -> Fixtures.json(json)).isInstanceOf(CollectionException.class);
    }

    @Test
    void rejectsInvalidUtf8AndDeepJson() {
        assertThatThrownBy(() -> JsonSupport.parse(new byte[]{'{', '"', (byte) 0xc3, '"', ':', '1', '}'}, "test", 0))
                .isInstanceOf(CollectionException.class);
        assertThatThrownBy(() -> Fixtures.json("{\"x\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"))
                .isInstanceOf(CollectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://private.invalid/data", "file:///data", "\\\\host\\share\\data", "//host/share", "*.json", "x?.json"})
    void rejectsNonLocalPaths(String path) {
        assertThatThrownBy(() -> JsonSupport.path(path, root, "input")).isInstanceOf(CollectionException.class);
    }

    @Test
    void enforcesActualByteBoundsWithoutReadingRemainder() throws IOException {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[100]);
        assertThatThrownBy(() -> JsonSupport.bounded(stream, 10, () -> { }, CollectionException.Code.LIMIT, "test"))
                .isInstanceOf(CollectionException.class);
        assertThat(stream.available()).isEqualTo(89);
        assertThat(JsonSupport.bounded(new ByteArrayInputStream(new byte[10]), 10, () -> { },
                CollectionException.Code.LIMIT, "test")).hasSize(10);
        Files.write(root.resolve("big.json"), new byte[JsonSupport.MANIFEST_LIMIT + 1]);
        assertThatThrownBy(() -> JsonSupport.read(root.resolve("big.json"), JsonSupport.MANIFEST_LIMIT, "test"))
                .isInstanceOf(CollectionException.class);
        AtomicInteger read = new AtomicInteger();
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                read.incrementAndGet();
                return ' ';
            }

            @Override
            public int read(byte[] bytes, int offset, int length) {
                read.addAndGet(length);
                return length;
            }
        };
        assertThatThrownBy(() -> JsonSupport.bounded(endless, JsonSupport.LINES_LIMIT, () -> { },
                CollectionException.Code.LIMIT, "test")).isInstanceOf(CollectionException.class);
        assertThat(read.get()).isEqualTo(JsonSupport.LINES_LIMIT + 1);
    }

    @Test
    void enforcesLineAndCaseBoundsAndBlankLines() {
        assertThatThrownBy(() -> JsonSupport.lines(Fixtures.bytes(" ".repeat(JsonSupport.LINE_LIMIT + 1)), "test"))
                .isInstanceOf(CollectionException.class);
        assertThatThrownBy(() -> JsonSupport.lines(Fixtures.bytes("{}\n".repeat(10_001)), "test"))
                .isInstanceOf(CollectionException.class);
        assertThat(JsonSupport.lines(Fixtures.bytes("{}\n".repeat(10_000)), "test")).hasSize(10_000);
        assertThat(JsonSupport.lines(Fixtures.bytes("{}" + " ".repeat(JsonSupport.LINE_LIMIT - 2)), "test")).hasSize(1);
        assertThatThrownBy(() -> JsonSupport.lines(Fixtures.bytes("{}\n\n"), "test")).isInstanceOf(CollectionException.class);
    }

    @Test
    void agreesWithExistingReaderOnCrlfAndExactLongs() throws IOException {
        Fixtures.files(root, "a");
        String label = """
                {"caseId":"a","tags":["term"],"expectedEmpty":false,"relevance":[
                  {"docId":9223372036854775807,"versionId":9007199254740993,"grade":3}],"note":{"ignored":"yes"}}
                """.replace("\n", " ") + "\r\n";
        Files.writeString(root.resolve("labels.jsonl"), label);
        Inputs.Batch batch = read();
        var dataset = new InputReader().readDataset(root.resolve("dataset.json").toString());
        assertThat(batch.labelsSha256()).isEqualTo(dataset.labelsSha256()).isEqualTo(JsonSupport.sha256(Fixtures.bytes(label)));
        assertThat(batch.datasetSha256()).isEqualTo(dataset.manifestSha256());
        assertThat(batch.cases().getFirst().caseId()).isEqualTo("a");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"caseId\":\"a\",\"tags\":[],\"expectedEmpty\":false,\"relevance\":[]}",
            "{\"caseId\":\"a\",\"tags\":[\"UNTAGGED\"],\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"a\",\"tags\":[\"x\",\"x\"],\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"a\",\"tags\":[],\"expectedEmpty\":true,\"relevance\":[{\"docId\":1,\"versionId\":2,\"grade\":1}]}",
            "{\"caseId\":\"a\",\"tags\":[],\"expectedEmpty\":true,\"relevance\":[{\"docId\":1,\"versionId\":2,\"grade\":0},{\"docId\":1,\"versionId\":2,\"grade\":0}]}",
            "{\"caseId\":\"a\",\"tags\":[],\"expectedEmpty\":false,\"relevance\":[{\"docId\":9223372036854775808,\"versionId\":2,\"grade\":3}]}",
            "{\"caseId\":\"a\",\"tags\":[],\"expectedEmpty\":false,\"relevance\":[{\"docId\":1,\"versionId\":2,\"grade\":4}]}"
    })
    void rejectsSameInvalidLabelsAsEvaluator(String label) throws IOException {
        Fixtures.files(root, "a");
        Files.writeString(root.resolve("labels.jsonl"), label);
        assertThatThrownBy(this::read).isInstanceOf(CollectionException.class);
        assertThatThrownBy(() -> new InputReader().readDataset(root.resolve("dataset.json").toString()))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"query\":\" \"}", "{\"query\":\"x\",\"userId\":1}", "{\"query\":\"x\",\"topK\":1}",
            "{\"query\":\"x\",\"spaceId\":1.0}", "{\"query\":\"x\",\"spaceId\":\"1\"}",
            "{\"query\":\"x\",\"categoryId\":1,\"uncategorized\":true}", "{\"query\":\"x\",\"uncategorized\":null}",
            "{\"query\":\"x\",\"tagIds\":null}", "{\"query\":\"x\",\"tagIds\":[1,1]}",
            "{\"query\":\"x\",\"fileType\":null}", "{\"query\":\"x\",\"fileType\":\"UNPUBLISHED\"}",
            "{\"query\":\"x\",\"updatedFrom\":\"2026-11-05\"}", "{\"query\":\"x\",\"updatedTo\":null}",
            "{\"query\":\"x\",\"updatedFrom\":\"2026-11-05T00:00:00Z\",\"updatedTo\":\"2026-11-04T00:00:00Z\"}"})
    void rejectsInvalidSearchRequests(String request) {
        assertThatThrownBy(() -> Inputs.request(Fixtures.json(request), 1)).isInstanceOf(CollectionException.class);
    }

    @Test
    void unicodeAndEncodingLimitsPreserveOriginalQuery() {
        ObjectNode request = Fixtures.json("{\"query\":\"  原问题  \"}");
        assertThat(Inputs.request(request, 1)).isFalse();
        assertThat(request.path("query").textValue()).isEqualTo("  原问题  ");
        for (String query : List.of("x".repeat(8001), "\u0000", "\uD800", "\uDC00")) {
            request.put("query", query);
            assertThatThrownBy(() -> Inputs.request(request, 1)).isInstanceOf(CollectionException.class);
        }
        request.put("query", "😀".repeat(8000));
        assertThat(Inputs.request(request, 1)).isFalse();
        assertThat(JsonSupport.encode(request).length).isLessThanOrEqualTo(JsonSupport.LINE_LIMIT);
        request.put("query", "x");
        request.putArray("tagIds").addAll(JsonSupport.MAPPER.createArrayNode().add(1).add(2));
        assertThat(Inputs.request(request, 1)).isTrue();
        for (int i = 3; i <= 21; i++) {
            ((ArrayNode) request.path("tagIds")).add(i);
        }
        assertThatThrownBy(() -> Inputs.request(request, 1)).isInstanceOf(CollectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"topK\":null", "\"topK\":201", "\"candidates\":0", "\"candidates\":1.5",
            "\"connectTimeoutMs\":60001", "\"requestTimeoutMs\":1", "\"maxResponseBytes\":0",
            "\"maxResponseBytes\":67108865", "\"allowHttpLoopback\":\"true\"", "\"token\":\"secret\"",
            "\"schemaVersion\":2", "\"runId\":\"invalid value\""})
    void rejectsInvalidConfiguration(String field) {
        ObjectNode config = Fixtures.config();
        config.setAll(Fixtures.json("{" + field + "}"));
        assertThatThrownBy(() -> Inputs.config(config)).isInstanceOf(CollectionException.class);
    }

    @Test
    void defaultsAndTopKAboveCandidatesAreLegal() {
        Inputs.Config defaults = Inputs.config(Fixtures.config());
        assertThat(defaults.candidates()).isEqualTo(50);
        assertThat(defaults.topK()).isEqualTo(20);
        assertThat(defaults.connectTimeoutMs()).isEqualTo(5000);
        assertThat(defaults.requestTimeoutMs()).isEqualTo(15000);
        ObjectNode config = Fixtures.config().put("candidates", 20).put("topK", 50);
        ObjectNode identity = (ObjectNode) config.path("identities").path("staff");
        identity.put("expectedUserId", Long.MAX_VALUE);
        assertThat(Inputs.config(config).identities().get("staff").expectedUserId()).isEqualTo(Long.MAX_VALUE);
        assertThat(Inputs.config(config).topK()).isEqualTo(50);
        identity.put("tokenEnv", "literal token value");
        assertThatThrownBy(() -> Inputs.config(config)).isInstanceOf(CollectionException.class);
    }

    @Test
    void encodedBodyHasAnIndependentByteLimit() {
        ObjectNode request = JsonSupport.MAPPER.createObjectNode().put("query", "x".repeat(JsonSupport.LINE_LIMIT - 12));
        assertThat(Inputs.encodeRequest(request, 1)).hasSize(JsonSupport.LINE_LIMIT);
        request.put("query", "x".repeat(JsonSupport.LINE_LIMIT - 11));
        assertThatThrownBy(() -> Inputs.encodeRequest(request, 1)).isInstanceOf(CollectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"tokenEnv\":\"TOKEN\",\"expectedUserId\":0}",
            "{\"tokenEnv\":\"TOKEN\",\"expectedUserId\":\"7\"}",
            "{\"tokenEnv\":\"TOKEN\",\"expectedUserId\":7.0}",
            "{\"tokenEnv\":\"TOKEN\",\"expectedUserId\":9223372036854775808}",
            "{\"token\":\"secret\",\"expectedUserId\":7}"})
    void rejectsInvalidIdentityConfiguration(String identity) {
        ObjectNode config = Fixtures.config();
        ((ObjectNode) config.path("identities")).set("staff", Fixtures.json(identity));
        assertThatThrownBy(() -> Inputs.config(config)).isInstanceOf(CollectionException.class);
    }

    @Test
    void coverageIdentityAndUnknownCaseFieldsAreStrict() {
        Inputs.Config config = Inputs.config(Fixtures.config());
        for (String cases : List.of(Fixtures.item("a", "staff"), Fixtures.item("a", "staff") + "\n" + Fixtures.item("a", "staff"),
                Fixtures.item("unknown", "staff"), Fixtures.item("a", "missing"))) {
            assertThatThrownBy(() -> Inputs.cases(Fixtures.bytes(cases), config, Set.of("a", "b")))
                    .isInstanceOf(CollectionException.class);
        }
        assertThatThrownBy(() -> Inputs.cases(Fixtures.bytes(Fixtures.item("a", "staff").replace("\"identity\"", "\"user\"")),
                config, Set.of("a"))).isInstanceOf(CollectionException.class);
    }

    @Test
    void frozenBytesAndCaseOrderSurviveInputChanges() throws IOException {
        Fixtures.files(root, "b", "a");
        Inputs.Batch batch = read();
        String original = batch.casesSha256();
        Files.writeString(root.resolve("cases.jsonl"), "changed");
        Files.writeString(root.resolve("labels.jsonl"), "changed");
        assertThat(batch.cases()).extracting(Inputs.Case::caseId).containsExactly("b", "a");
        assertThat(batch.casesSha256()).isEqualTo(original);
        byte[] body = batch.cases().getFirst().body();
        body[0] = 'x';
        assertThat(batch.cases().getFirst().body()[0]).isEqualTo((byte) '{');
    }

    private Inputs.Batch read() {
        CollectionCli.Options options = Fixtures.options(root);
        return new Inputs().read(options.dataset(), options.cases(), options.config());
    }
}
