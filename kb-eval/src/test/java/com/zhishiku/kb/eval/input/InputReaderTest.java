package com.zhishiku.kb.eval.input;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.model.InputModel.VersionKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InputReaderTest {
    private static final String LABEL = """
            {"caseId":"c1","tags":[],"expectedEmpty":false,"relevance":[{"docId":1,"versionId":2,"grade":3}]}
            """;
    private static final String HIT = "{\"docId\":1,\"versionId\":2,\"chunkId\":3}";
    private final InputReader reader = new InputReader();
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir
    Path directory;
    private Path dataset;
    private Path run;

    @BeforeEach
    void files() throws Exception {
        dataset = write("dataset.json", """
                {"schemaVersion":1,"datasetId":"test","datasetVersion":"v1","corpusSnapshotId":"s1","labelsFile":"labels.jsonl"}
                """);
        write("labels.jsonl", LABEL);
        write("results.jsonl", result(HIT));
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1).put("datasetId", "test").put("datasetVersion", "v1")
                .put("corpusSnapshotId", "s1").put("runId", "r1").put("configurationId", "config1")
                .put("sourceType", "SYNTHETIC").put("resultsFile", "results.jsonl")
                .put("labelsSha256", hash(Files.readAllBytes(directory.resolve("labels.jsonl"))));
        run = write("run.json", manifest.toString());
    }

    @Test
    void readsRelativeFilesAndHashesExactBytes() throws Exception {
        byte[] bytes = LABEL.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("labels.jsonl"), bytes);
        mutate(run, "labelsSha256", hash(bytes));
        var inputs = reader.read(dataset.toString(), List.of(run.toString()), 20);
        assertThat(inputs.dataset().labelsSha256()).isEqualTo(hash(bytes));
        assertThat(inputs.dataset().manifestSha256()).isEqualTo(hash(Files.readAllBytes(dataset)));
        assertThat(inputs.runs().getFirst().resultsSha256())
                .isEqualTo(hash(Files.readAllBytes(directory.resolve("results.jsonl"))));
        assertThat(inputs.dataset().cases().get("c1").tags()).isEmpty();
        assertThat(inputs.runs().getFirst().cases().get("c1").hits()).hasSize(1);
    }

    @Test
    void acceptsMixedRelevantAndExpectedEmptyCasesWithGradeZero() throws Exception {
        write("labels.jsonl", LABEL + "{\"caseId\":\"c2\",\"tags\":[],\"expectedEmpty\":true," +
                "\"relevance\":[{\"docId\":9,\"versionId\":10,\"grade\":0}]}\n");
        Dataset data = reader.readDataset(dataset.toString());
        mutate(run, "labelsSha256", data.labelsSha256());
        write("results.jsonl", result(HIT) + "\n{\"caseId\":\"c2\",\"status\":\"OK\",\"hits\":[]}\n");
        var result = reader.readRun(run.toString(), data, 20, "run[1]");
        assertThat(data.cases().get("c2").expectedEmpty()).isTrue();
        assertThat(data.cases().get("c2").relevance()).containsEntry(new VersionKey(9, 10), 0);
        assertThat(result.cases()).hasSize(2);
        assertThat(result.cases().get("c2").totalHits()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"datasetId", "datasetVersion", "corpusSnapshotId", "labelsSha256"})
    void rejectsMismatchedIdentity(String field) throws Exception {
        mutate(run, field, "different");
        assertThatThrownBy(() -> reader.readRun(run.toString(), reader.readDataset(dataset.toString()), 20, "run[1]"))
                .isInstanceOf(EvaluationException.class);
    }

    @Test
    void rejectsUnsupportedAndUnknownManifestFieldsAndDuplicateProperties() throws Exception {
        mutate(dataset, "newField", "secret");
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).isInstanceOf(EvaluationException.class);
        write("dataset.json", "{\"schemaVersion\":1,\"schemaVersion\":1}");
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("INVALID_JSON");
        write("dataset.json", "{\"schemaVersion\":2}");
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("UNSUPPORTED_SCHEMA");
        write("dataset.json", "{} {}");
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("INVALID_JSON");
    }

    @Test
    void rejectsRunDuplicatesAndUnknownSourceAndFields() throws Exception {
        assertThatThrownBy(() -> reader.read(dataset.toString(), List.of(run.toString(), run.toString()), 20))
                .hasMessage("DUPLICATE");
        mutate(run, "sourceType", "ONLINE");
        assertThatThrownBy(() -> reader.readRun(run.toString(), reader.readDataset(dataset.toString()), 20, "run[1]"))
                .hasMessage("INVALID_FIELD");
        mutate(run, "sourceType", "RECORDED");
        mutate(run, "query", "secret");
        assertThatThrownBy(() -> reader.readRun(run.toString(), reader.readDataset(dataset.toString()), 20, "run[1]"))
                .hasMessage("INVALID_FIELD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://localhost/x", "file:///x", "redis://localhost/x", "\\\\server\\share\\x",
            "\\/server/share/x", "/\\server/share/x", "missing.json"})
    void rejectsNonlocalAndMissingFiles(String file) {
        assertThatThrownBy(() -> reader.readDataset(file)).hasMessage("LOCAL_FILE_REQUIRED");
    }

    @Test
    void hashesAndParsesOneReadWithoutReopeningLabels() throws Exception {
        Dataset original = reader.readDataset(dataset.toString());
        write("labels.jsonl", "changed-after-read");
        assertThat(reader.readRun(run.toString(), original, 20, "run[1]").cases()).hasSize(1);
    }

    @Test
    void rejectsMalformedUtf8AndEmptyDataset() throws Exception {
        Files.write(directory.resolve("labels.jsonl"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("READ_FAILED");
        write("labels.jsonl", "");
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("COVERAGE_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.0", "\"1\"", "true", "null", "9223372036854775808"})
    void rejectsIdentityCoercionAndLongOverflow(String value) throws Exception {
        write("labels.jsonl", LABEL.replace("\"docId\":1", "\"docId\":" + value));
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("INVALID_FIELD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "4", "1.0", "\"3\"", "true", "null", "4294967296"})
    void rejectsInvalidGrades(String value) throws Exception {
        write("labels.jsonl", LABEL.replace("\"grade\":3", "\"grade\":" + value));
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("INVALID_FIELD");
    }

    @Test
    void permitsDistinctVersionsAndLongMaximumAndGradeZero() throws Exception {
        write("labels.jsonl", """
                {"caseId":"c1","tags":["term","synonym"],"expectedEmpty":false,"relevance":[
                {"docId":9223372036854775807,"versionId":2,"grade":3},{"docId":9223372036854775807,"versionId":4,"grade":0}]}
                """.replace("\n", ""));
        assertLabels(reader.readDataset(dataset.toString()));
    }

    private static void assertLabels(Dataset data) {
        assertThat(data.cases().get("c1").relevance()).containsEntry(new VersionKey(Long.MAX_VALUE, 2), 3)
                .containsEntry(new VersionKey(Long.MAX_VALUE, 4), 0);
        assertThat(data.cases().get("c1").tags()).containsExactly("synonym", "term");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"caseId\":\"c1\",\"tags\":[],\"expectedEmpty\":true,\"relevance\":[{\"docId\":1,\"versionId\":2," +
                    "\"grade\":3}]}",
            "{\"caseId\":\"c1\",\"tags\":[],\"expectedEmpty\":false,\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"tags\":[\"a\",\"a\"],\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"tags\":[\"UNTAGGED\"],\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"tags\":[3],\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"expectedEmpty\":true,\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"tags\":[],\"expectedEmpty\":\"true\",\"relevance\":[]}",
            "{\"caseId\":\"c1\",\"tags\":[],\"expectedEmpty\":false,\"relevance\":[{\"docId\":1,\"versionId\":2," +
                    "\"grade\":3},{\"docId\":1,\"versionId\":2,\"grade\":1}]}"
    })
    void rejectsInvalidLabelStructures(String value) throws Exception {
        write("labels.jsonl", value);
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).isInstanceOf(EvaluationException.class);
    }

    @Test
    void rejectsDuplicateCasesAndUnknownNestedRelevance() throws Exception {
        write("labels.jsonl", LABEL + LABEL);
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("DUPLICATE");
        write("labels.jsonl", LABEL.replace("\"grade\":3", "\"grade\":3,\"content\":\"secret\""));
        assertThatThrownBy(() -> reader.readDataset(dataset.toString())).hasMessage("INVALID_FIELD");
    }

    @Test
    void skipsExtraFieldsAndKeepsArrayOrderAndPrefix() throws Exception {
        write("labels.jsonl", LABEL.replace("\"caseId\"", "\"query\":{\"nested\":[\"SECRET\"]},\"caseId\""));
        Dataset data = reader.readDataset(dataset.toString());
        mutate(run, "labelsSha256", data.labelsSha256());
        write("results.jsonl", result(HIT.replace("\"chunkId\":3", "\"chunkId\":3,\"content\":\"SECRET\",\"score\":0")
                + ",{\"docId\":1,\"versionId\":2,\"chunkId\":4,\"score\":100}")
                .replace("\"status\"", "\"query\":\"SECRET\",\"status\""));
        var result = reader.readRun(run.toString(), data, 1, "run[1]").cases().get("c1");
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().getFirst().chunkId()).isEqualTo(3);
        assertThat(result.totalHits()).isEqualTo(2);
        assertThat(data.toString() + result).doesNotContain("SECRET");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"caseId\":\"c2\",\"status\":\"OK\",\"hits\":[]}",
            "{\"caseId\":\"c1\",\"status\":\"ERROR\"}", "{\"caseId\":\"c1\",\"status\":\"OK\"}",
            "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":null}",
            "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[{\"docId\":1,\"chunkId\":3}]}",
            "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[],\"latencyMs\":-1}",
            "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[],\"latencyMs\":1e999}",
            "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[],\"latencyMs\":NaN}",
            "{\"caseId\":\"c1\",\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[]}"})
    void rejectsMissingUnknownFailedAndInvalidResults(String value) throws Exception {
        write("results.jsonl", value);
        assertThatThrownBy(() -> reader.readRun(run.toString(), reader.readDataset(dataset.toString()), 20, "run[1]"))
                .isInstanceOf(EvaluationException.class);
    }

    @Test
    void validatesHitsBeyondBudgetAndDuplicateResults() throws Exception {
        Dataset data = reader.readDataset(dataset.toString());
        for (String value : List.of(result(HIT + "," + HIT), result(HIT + ",{\"docId\":1,\"chunkId\":5}"),
                result(HIT + ",{\"docId\":1,\"versionId\":2,\"chunkId\":0}"), result(HIT) + "\n" + result(HIT))) {
            write("results.jsonl", value);
            assertThatThrownBy(() -> reader.readRun(run.toString(), data, 1, "run[1]"))
                    .isInstanceOf(EvaluationException.class);
        }
    }

    @Test
    void acceptsSuccessEmptyAndOptionalLatency() throws Exception {
        Dataset data = reader.readDataset(dataset.toString());
        write("results.jsonl", result(""));
        assertThat(reader.readRun(run.toString(), data, 20, "run[1]").cases().get("c1").latencyMs()).isNull();
        write("results.jsonl", result("").replace("\"hits\"", "\"latencyMs\":0,\"hits\""));
        var result = reader.readRun(run.toString(), data, 20, "run[1]").cases().get("c1");
        assertThat(result.hits()).isEmpty();
        assertThat(result.totalHits()).isZero();
        assertThat(result.latencyMs()).isEqualTo(0.0);
        write("results.jsonl", result("").replace("\"hits\"", "\"latencyMs\":null,\"hits\""));
        assertThat(reader.readRun(run.toString(), data, 20, "run[1]").cases().get("c1").latencyMs()).isNull();
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(directory.resolve(name), content, StandardCharsets.UTF_8);
    }

    private void mutate(Path path, String field, String value) throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(Files.readAllBytes(path));
        node.put(field, value);
        Files.writeString(path, node.toString(), StandardCharsets.UTF_8);
    }

    private static String result(String hits) {
        return "{\"caseId\":\"c1\",\"status\":\"OK\",\"hits\":[" + hits + "]}";
    }

    private static String hash(byte[] bytes) {
        return HexFormat.of().formatHex(JsonInput.digest().digest(bytes));
    }
}
