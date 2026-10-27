package com.zhishiku.kb.eval.input;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.eval.input.EvaluationException.Code;
import com.zhishiku.kb.eval.input.JsonInput.Document;
import com.zhishiku.kb.eval.model.InputModel.Dataset;
import com.zhishiku.kb.eval.model.InputModel.Hit;
import com.zhishiku.kb.eval.model.InputModel.Identity;
import com.zhishiku.kb.eval.model.InputModel.Inputs;
import com.zhishiku.kb.eval.model.InputModel.LabelCase;
import com.zhishiku.kb.eval.model.InputModel.ResultCase;
import com.zhishiku.kb.eval.model.InputModel.Run;
import com.zhishiku.kb.eval.model.InputModel.SourceType;
import com.zhishiku.kb.eval.model.InputModel.VersionKey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zhishiku.kb.eval.input.JsonInput.error;

/**
 * 完整输入校验先于指标计算，读取与摘要使用同一字节流。
 */
public final class InputReader {
    private static final Set<String> DATASET_FIELDS = Set.of("schemaVersion", "datasetId", "datasetVersion",
            "corpusSnapshotId", "labelsFile");
    private static final Set<String> RUN_FIELDS = Set.of("schemaVersion", "runId", "datasetId", "datasetVersion",
            "corpusSnapshotId", "configurationId", "sourceType", "labelsSha256", "resultsFile");
    private static final Set<String> LABEL_FIELDS = Set.of("caseId", "tags", "expectedEmpty", "relevance");
    private static final Set<String> RELEVANCE_FIELDS = Set.of("docId", "versionId", "grade");
    private static final Set<String> HIT_FIELDS = Set.of("docId", "versionId", "chunkId");

    public Inputs read(String datasetFile, List<String> runFiles, int maxK) {
        Dataset dataset = readDataset(datasetFile);
        Set<String> ids = new HashSet<>();
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < runFiles.size(); i++) {
            Run run = readRun(runFiles.get(i), dataset, maxK, "run[" + (i + 1) + "]");
            if (!ids.add(run.runId())) {
                throw error(Code.DUPLICATE, "run[" + (i + 1) + "]", 0, "runId");
            }
            runs.add(run);
        }
        return new Inputs(dataset, runs);
    }

    public Dataset readDataset(String file) {
        Document manifest = JsonInput.readDocument(file, "dataset");
        JsonInput.fields(manifest.object(), DATASET_FIELDS, "dataset", 0);
        JsonInput.schema(manifest.object(), "dataset");
        Identity identity = identity(manifest.object(), "dataset");
        Path labels = JsonInput.localPath(JsonInput.text(manifest.object(), "labelsFile", "dataset", 0),
                manifest.path().getParent(), "dataset.labels", "labelsFile");
        Map<String, LabelCase> cases = new HashMap<>();
        String digest = lines(labels, "dataset.labels", (parser, line) -> {
            LabelCase label = label(parser, line);
            if (cases.putIfAbsent(label.caseId(), label) != null) {
                throw error(Code.DUPLICATE, "dataset.labels", line, "caseId");
            }
        });
        if (cases.isEmpty()) {
            throw error(Code.COVERAGE_MISMATCH, "dataset.labels", 0, "caseId");
        }
        return new Dataset(identity, manifest.sha256(), digest, cases);
    }

    public Run readRun(String file, Dataset dataset, int maxK, String role) {
        Document manifest = JsonInput.readDocument(file, role);
        ObjectNode object = manifest.object();
        JsonInput.fields(object, RUN_FIELDS, role, 0);
        JsonInput.schema(object, role);
        if (!identity(object, role).equals(dataset.identity())) {
            throw error(Code.IDENTITY_MISMATCH, role, 0, "datasetId");
        }
        String runId = JsonInput.identifier(object, "runId", role, 0);
        String configurationId = JsonInput.identifier(object, "configurationId", role, 0);
        String source = JsonInput.text(object, "sourceType", role, 0);
        if (!Set.of("SYNTHETIC", "RECORDED").contains(source)) {
            throw error(Code.INVALID_FIELD, role, 0, "sourceType");
        }
        String declaredHash = JsonInput.text(object, "labelsSha256", role, 0);
        if (!declaredHash.matches("[0-9a-fA-F]{64}") || !declaredHash.equalsIgnoreCase(dataset.labelsSha256())) {
            throw error(Code.DIGEST_MISMATCH, role, 0, "labelsSha256");
        }
        Path results = JsonInput.localPath(JsonInput.text(object, "resultsFile", role, 0),
                manifest.path().getParent(), role + ".results", "resultsFile");
        Map<String, ResultCase> cases = new HashMap<>();
        String digest = lines(results, role + ".results", (parser, line) -> {
            ResultCase result = result(parser, role + ".results", line, maxK);
            if (!dataset.cases().containsKey(result.caseId())) {
                throw error(Code.COVERAGE_MISMATCH, role + ".results", line, "caseId");
            }
            if (cases.putIfAbsent(result.caseId(), result) != null) {
                throw error(Code.DUPLICATE, role + ".results", line, "caseId");
            }
        });
        if (!cases.keySet().equals(dataset.cases().keySet())) {
            throw error(Code.COVERAGE_MISMATCH, role + ".results", 0, "caseId");
        }
        return new Run(runId, configurationId, SourceType.valueOf(source), manifest.sha256(), digest, cases);
    }

    private static Identity identity(JsonNode node, String role) {
        return new Identity(JsonInput.identifier(node, "datasetId", role, 0),
                JsonInput.identifier(node, "datasetVersion", role, 0),
                JsonInput.identifier(node, "corpusSnapshotId", role, 0));
    }

    private static LabelCase label(JsonParser parser, int line) throws IOException {
        String role = "dataset.labels";
        ObjectNode node = JsonInput.selectedObject(parser, LABEL_FIELDS, true, role, line);
        String caseId = JsonInput.identifier(node, "caseId", role, line);
        JsonNode rawTags = JsonInput.required(node, "tags", role, line);
        if (!rawTags.isArray()) {
            throw error(Code.INVALID_FIELD, role, line, "tags");
        }
        List<String> tags = new ArrayList<>();
        Set<String> tagSet = new HashSet<>();
        for (JsonNode tag : rawTags) {
            if (!tag.isTextual() || !tag.textValue().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || tag.textValue().equals("UNTAGGED")) {
                throw error(Code.INVALID_FIELD, role, line, "tags");
            }
            if (!tagSet.add(tag.textValue())) {
                throw error(Code.DUPLICATE, role, line, "tags");
            }
            tags.add(tag.textValue());
        }
        tags.sort(String::compareTo);
        JsonNode empty = JsonInput.required(node, "expectedEmpty", role, line);
        if (!empty.isBoolean()) {
            throw error(Code.INVALID_FIELD, role, line, "expectedEmpty");
        }
        JsonNode relevance = JsonInput.required(node, "relevance", role, line);
        if (!relevance.isArray()) {
            throw error(Code.INVALID_FIELD, role, line, "relevance");
        }
        Map<VersionKey, Integer> grades = new HashMap<>();
        for (JsonNode item : relevance) {
            JsonInput.fields(item, RELEVANCE_FIELDS, role, line);
            VersionKey key = new VersionKey(JsonInput.positiveId(item, "docId", role, line),
                    JsonInput.positiveId(item, "versionId", role, line));
            JsonNode grade = JsonInput.required(item, "grade", role, line);
            if (!grade.isIntegralNumber() || !grade.canConvertToInt() || grade.intValue() < 0 || grade.intValue() > 3) {
                throw error(Code.INVALID_FIELD, role, line, "grade");
            }
            if (grades.putIfAbsent(key, grade.intValue()) != null) {
                throw error(Code.DUPLICATE, role, line, "relevance");
            }
        }
        boolean positive = grades.values().stream().anyMatch(grade -> grade > 0);
        if (empty.booleanValue() == positive) {
            throw error(Code.INVALID_FIELD, role, line, "expectedEmpty");
        }
        return new LabelCase(caseId, tags, empty.booleanValue(), grades);
    }

    private static ResultCase result(JsonParser parser, String role, int line, int maxK) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw error(Code.INVALID_JSON, role, line, "json");
        }
        String caseId = null;
        String status = null;
        Double latency = null;
        List<Hit> hits = null;
        int total = 0;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw error(Code.INVALID_JSON, role, line, "json");
            }
            String field = parser.currentName();
            if (parser.nextToken() == null) {
                throw error(Code.INVALID_JSON, role, line, "json");
            }
            switch (field) {
                case "caseId" -> {
                    if (parser.currentToken() != JsonToken.VALUE_STRING
                            || !parser.getText().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                        throw error(Code.INVALID_FIELD, role, line, "caseId");
                    }
                    caseId = parser.getText();
                }
                case "status" -> {
                    if (parser.currentToken() != JsonToken.VALUE_STRING) {
                        throw error(Code.INVALID_FIELD, role, line, "status");
                    }
                    status = parser.getText();
                }
                case "latencyMs" -> {
                    if (parser.currentToken() != JsonToken.VALUE_NULL) {
                        if (!parser.currentToken().isNumeric() || !Double.isFinite(parser.getDoubleValue()) || parser.getDoubleValue() < 0) {
                            throw error(Code.INVALID_FIELD, role, line, "latencyMs");
                        }
                        latency = parser.getDoubleValue();
                    }
                }
                case "hits" -> {
                    if (parser.currentToken() != JsonToken.START_ARRAY) {
                        throw error(Code.INVALID_FIELD, role, line, "hits");
                    }
                    hits = new ArrayList<>();
                    Set<Hit> unique = new HashSet<>();
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        ObjectNode hitNode = JsonInput.selectedObject(parser, HIT_FIELDS, true, role, line);
                        Hit hit = new Hit(JsonInput.positiveId(hitNode, "docId", role, line),
                                JsonInput.positiveId(hitNode, "versionId", role, line),
                                JsonInput.positiveId(hitNode, "chunkId", role, line));
                        if (!unique.add(hit)) {
                            throw error(Code.DUPLICATE, role, line, "hits");
                        }
                        if (total < maxK) {
                            hits.add(hit);
                        }
                        total++;
                    }
                }
                default -> parser.skipChildren();
            }
        }
        if ("ERROR".equals(status)) {
            throw error(Code.EXECUTION_FAILED, role, line, "status");
        }
        if (!"OK".equals(status)) {
            throw error(Code.INVALID_FIELD, role, line, "status");
        }
        if (caseId == null || hits == null) {
            throw error(Code.INVALID_FIELD, role, line, caseId == null ? "caseId" : "hits");
        }
        return new ResultCase(caseId, hits, total, latency);
    }

    @FunctionalInterface
    private interface LineConsumer {
        void accept(JsonParser parser, int line) throws IOException;
    }

    private static String lines(Path path, String role, LineConsumer consumer) {
        MessageDigest digest = JsonInput.digest();
        int line = 0;
        try (DigestInputStream stream = new DigestInputStream(Files.newInputStream(path), digest);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                     StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                line++;
                try (JsonParser parser = JsonInput.parser(raw)) {
                    if (parser.nextToken() == null) {
                        throw error(Code.INVALID_JSON, role, line, "json");
                    }
                    consumer.accept(parser, line);
                    if (parser.nextToken() != null) {
                        throw error(Code.INVALID_JSON, role, line, "json");
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException e) {
            throw error(Code.INVALID_JSON, role, line, "json");
        } catch (IOException | SecurityException e) {
            throw error(Code.READ_FAILED, role, line, "file");
        }
    }
}
