package com.zhishiku.kb.eval.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.zhishiku.kb.eval.collector.CollectionException.Code.INPUT;
import static com.zhishiku.kb.eval.collector.JsonSupport.identifier;
import static com.zhishiku.kb.eval.collector.JsonSupport.positive;
import static com.zhishiku.kb.eval.collector.JsonSupport.text;

/** 所有本地内容在任何网络调用前读取、核对并冻结。 */
final class Inputs {
    /**
     * 预期会话绑定。
     * @param tokenEnv 环境变量名，不是令牌值
     * @param expectedUserId 预期内部用户的精确正整数 ID
     */
    record Identity(String tokenEnv, long expectedUserId) {
    }

    /**
     * 已校验的批次配置。
     * @param runId 运行标识
     * @param configurationId 操作者声明的配置标识
     * @param endpointId 输出可记录的端点逻辑标识
     * @param baseUrl 仅用于传输的服务根地址
     * @param identities 别名到预期身份的只读映射
     * @param candidates 搜索候选数
     * @param topK 搜索返回上限
     * @param connectTimeoutMs 连接期限，毫秒
     * @param requestTimeoutMs 整次请求期限，毫秒
     * @param maxResponseBytes 实际响应正文上限，字节
     */
    record Config(String runId, String configurationId, String endpointId, URI baseUrl,
                  Map<String, Identity> identities, int candidates, int topK, int connectTimeoutMs,
                  int requestTimeoutMs, int maxResponseBytes) {
        Config {
            identities = Collections.unmodifiableMap(new LinkedHashMap<>(identities));
        }
    }

    /**
     * 冻结的场景与发送内容。
     * @param caseId 标注场景标识
     * @param identity 身份别名
     * @param body 注入数量后的 UTF-8 请求字节
     * @param request 原请求，用于稳定绑定摘要
     * @param filtered 是否存在有效筛选
     * @param line 原用例行号，从 1 开始
     */
    record Case(String caseId, String identity, byte[] body, JsonNode request, boolean filtered, int line) {
        Case {
            body = body.clone();
            request = request.deepCopy();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public JsonNode request() {
            return request.deepCopy();
        }
    }

    /**
     * 网络调用前已经完成核对的完整批次。
     * @param datasetId 数据集标识
     * @param datasetVersion 数据集版本
     * @param corpusSnapshotId 操作者声明的语料快照
     * @param config 批次配置
     * @param cases 原文件顺序的只读场景列表
     * @param datasetSha256 实际数据集清单摘要
     * @param labelsSha256 实际标注字节摘要
     * @param casesSha256 实际用例字节摘要
     * @param scenarioBindingSha256 规范化场景绑定摘要
     */
    record Batch(String datasetId, String datasetVersion, String corpusSnapshotId, Config config,
                 List<Case> cases, String datasetSha256, String labelsSha256, String casesSha256,
                 String scenarioBindingSha256) {
        Batch {
            cases = List.copyOf(cases);
        }
    }

    private static final Set<String> CONFIG_FIELDS = Set.of("schemaVersion", "runId", "configurationId",
            "endpointId", "baseUrl", "identities", "candidates", "topK", "connectTimeoutMs",
            "requestTimeoutMs", "maxResponseBytes", "allowHttpLoopback");
    private static final Set<String> REQUEST_FIELDS = Set.of("query", "spaceId", "categoryId", "uncategorized",
            "tagIds", "fileType", "updatedFrom", "updatedTo");

    Batch read(String datasetFile, String casesFile, String configFile) {
        Path datasetPath = JsonSupport.path(datasetFile, null, "dataset");
        byte[] datasetBytes = JsonSupport.read(datasetPath, JsonSupport.MANIFEST_LIMIT, "dataset");
        JsonNode dataset = JsonSupport.parse(datasetBytes, "dataset", 0);
        JsonSupport.fields(dataset, Set.of("schemaVersion", "datasetId", "datasetVersion", "corpusSnapshotId", "labelsFile"),
                "dataset", 0);
        JsonSupport.schema(dataset, "dataset");
        String datasetId = identifier(dataset, "datasetId", "dataset", 0);
        String datasetVersion = identifier(dataset, "datasetVersion", "dataset", 0);
        String corpusSnapshotId = identifier(dataset, "corpusSnapshotId", "dataset", 0);
        Path labelPath = JsonSupport.path(text(dataset, "labelsFile", "dataset", 0), datasetPath.getParent(), "labels");
        byte[] labelsBytes = JsonSupport.read(labelPath, JsonSupport.LINES_LIMIT, "labels");
        Set<String> labels = labels(labelsBytes);
        byte[] configBytes = JsonSupport.read(JsonSupport.path(configFile, null, "config"), JsonSupport.MANIFEST_LIMIT, "config");
        Config config = config(JsonSupport.parse(configBytes, "config", 0));
        byte[] casesBytes = JsonSupport.read(JsonSupport.path(casesFile, null, "cases"), JsonSupport.LINES_LIMIT, "cases");
        List<Case> cases = cases(casesBytes, config, labels);
        return new Batch(datasetId, datasetVersion, corpusSnapshotId, config, cases, JsonSupport.sha256(datasetBytes),
                JsonSupport.sha256(labelsBytes), JsonSupport.sha256(casesBytes), binding(cases, config));
    }

    static Config config(JsonNode node) {
        JsonSupport.fields(node, CONFIG_FIELDS, "config", 0);
        JsonSupport.schema(node, "config");
        String run = identifier(node, "runId", "config", 0);
        String configuration = identifier(node, "configurationId", "config", 0);
        String endpoint = identifier(node, "endpointId", "config", 0);
        URI base = Endpoint.validate(text(node, "baseUrl", "config", 0),
                JsonSupport.bool(node, "allowHttpLoopback", false, "config", 0));
        Map<String, Identity> identities = new LinkedHashMap<>();
        JsonNode bindings = node.path("identities");
        if (!bindings.isObject() || bindings.isEmpty()) {
            throw new CollectionException(INPUT, "config", 0, "identities");
        }
        bindings.fields().forEachRemaining(entry -> {
            if (!identifier(entry.getKey())) {
                throw new CollectionException(INPUT, "config", 0, "identities");
            }
            JsonNode item = entry.getValue();
            JsonSupport.fields(item, Set.of("tokenEnv", "expectedUserId"), "config", 0);
            String env = text(item, "tokenEnv", "config", 0);
            if (!env.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
                throw new CollectionException(INPUT, "config", 0, "tokenEnv");
            }
            identities.put(entry.getKey(), new Identity(env, positive(item.path("expectedUserId"), "config", 0, "expectedUserId")));
        });
        int connect = JsonSupport.integer(node, "connectTimeoutMs", 1, 60_000, 5000, "config");
        int timeout = JsonSupport.integer(node, "requestTimeoutMs", connect, 300_000, 15_000, "config");
        if (timeout < connect) {
            throw new CollectionException(INPUT, "config", 0, "requestTimeoutMs");
        }
        return new Config(run, configuration, endpoint, base, identities,
                JsonSupport.integer(node, "candidates", 1, 200, 50, "config"),
                JsonSupport.integer(node, "topK", 1, 200, 20, "config"), connect, timeout,
                JsonSupport.integer(node, "maxResponseBytes", 1, 64 * 1024 * 1024, 4 * 1024 * 1024, "config"));
    }

    static Set<String> labels(byte[] bytes) {
        Set<String> ids = new HashSet<>();
        int line = 0;
        for (JsonNode node : JsonSupport.lines(bytes, "labels")) {
            line++;
            String id = identifier(node, "caseId", "labels", line);
            if (!ids.add(id)) {
                throw new CollectionException(INPUT, "labels", line, "caseId");
            }
            JsonNode tags = node.path("tags");
            if (!tags.isArray() || !node.path("expectedEmpty").isBoolean() || !node.path("relevance").isArray()) {
                throw new CollectionException(INPUT, "labels", line, "json");
            }
            Set<String> uniqueTags = new HashSet<>();
            for (JsonNode tag : tags) {
                if (!tag.isTextual() || !identifier(tag.textValue()) || tag.textValue().equals("UNTAGGED")
                        || !uniqueTags.add(tag.textValue())) {
                    throw new CollectionException(INPUT, "labels", line, "tags");
                }
            }
            Set<List<Long>> versions = new HashSet<>();
            boolean hasPositive = false;
            for (JsonNode item : node.path("relevance")) {
                JsonSupport.fields(item, Set.of("docId", "versionId", "grade"), "labels", line);
                long doc = positive(item.path("docId"), "labels", line, "docId");
                long version = positive(item.path("versionId"), "labels", line, "versionId");
                if (!versions.add(List.of(doc, version))) {
                    throw new CollectionException(INPUT, "labels", line, "relevance");
                }
                JsonNode grade = item.path("grade");
                if (!grade.isIntegralNumber() || !grade.canConvertToInt() || grade.intValue() < 0 || grade.intValue() > 3) {
                    throw new CollectionException(INPUT, "labels", line, "grade");
                }
                hasPositive |= grade.intValue() > 0;
            }
            if (hasPositive == node.path("expectedEmpty").booleanValue()) {
                throw new CollectionException(INPUT, "labels", line, "expectedEmpty");
            }
        }
        return ids;
    }

    static List<Case> cases(byte[] bytes, Config config, Set<String> labels) {
        List<Case> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int line = 0;
        for (JsonNode node : JsonSupport.lines(bytes, "cases")) {
            line++;
            JsonSupport.fields(node, Set.of("caseId", "identity", "request"), "cases", line);
            String id = identifier(node, "caseId", "cases", line);
            String identity = identifier(node, "identity", "cases", line);
            if (!ids.add(id) || !labels.contains(id)) {
                throw new CollectionException(INPUT, "cases", line, "caseId");
            }
            if (!config.identities().containsKey(identity)) {
                throw new CollectionException(INPUT, "cases", line, "identity");
            }
            JsonNode request = node.path("request");
            boolean filtered = request(request, line);
            ObjectNode body = request.deepCopy();
            body.put("candidates", config.candidates());
            body.put("topK", config.topK());
            byte[] encoded = encodeRequest(body, line);
            result.add(new Case(id, identity, encoded, request, filtered, line));
        }
        if (!ids.equals(labels)) {
            throw new CollectionException(INPUT, "cases", 0, "caseId");
        }
        return result;
    }

    static byte[] encodeRequest(ObjectNode body, int line) {
        byte[] encoded = JsonSupport.encode(body);
        if (encoded.length > JsonSupport.LINE_LIMIT) {
            throw new CollectionException(CollectionException.Code.LIMIT, "cases", line, "request");
        }
        return encoded;
    }

    static boolean request(JsonNode node, int line) {
        JsonSupport.fields(node, REQUEST_FIELDS, "cases", line);
        String query = text(node, "query", "cases", line);
        if (query.codePointCount(0, query.length()) > 8000) {
            throw new CollectionException(INPUT, "cases", line, "query");
        }
        for (int index = 0; index < query.length(); index++) {
            char c = query.charAt(index);
            if (c == 0 || Character.isLowSurrogate(c)) {
                throw new CollectionException(INPUT, "cases", line, "query");
            }
            if (Character.isHighSurrogate(c)) {
                if (++index >= query.length() || !Character.isLowSurrogate(query.charAt(index))) {
                    throw new CollectionException(INPUT, "cases", line, "query");
                }
            }
        }
        boolean filtered = false;
        for (String field : List.of("spaceId", "categoryId")) {
            if (node.hasNonNull(field)) {
                positive(node.path(field), "cases", line, field);
                filtered = true;
            }
        }
        boolean uncategorized = JsonSupport.bool(node, "uncategorized", false, "cases", line);
        if (uncategorized && node.hasNonNull("categoryId")) {
            throw new CollectionException(INPUT, "cases", line, "uncategorized");
        }
        filtered |= uncategorized;
        if (node.has("tagIds")) {
            JsonNode tags = node.path("tagIds");
            if (!tags.isArray() || tags.size() > 20) {
                throw new CollectionException(INPUT, "cases", line, "tagIds");
            }
            Set<Long> ids = new HashSet<>();
            for (JsonNode tag : tags) {
                if (!ids.add(positive(tag, "cases", line, "tagIds"))) {
                    throw new CollectionException(INPUT, "cases", line, "tagIds");
                }
            }
            filtered |= !ids.isEmpty();
        }
        if (node.has("fileType")) {
            String type = text(node, "fileType", "cases", line);
            if (!Set.of("PDF", "TXT", "MARKDOWN", "WORD", "EXCEL", "POWERPOINT", "OTHER").contains(type)) {
                throw new CollectionException(INPUT, "cases", line, "fileType");
            }
            filtered = true;
        }
        OffsetDateTime from = time(node, "updatedFrom", line);
        OffsetDateTime to = time(node, "updatedTo", line);
        if (from != null && to != null && !from.toInstant().isBefore(to.toInstant())) {
            throw new CollectionException(INPUT, "cases", line, "updatedTo");
        }
        return filtered || from != null || to != null;
    }

    private static OffsetDateTime time(JsonNode node, String field, int line) {
        if (!node.has(field)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text(node, field, "cases", line));
        } catch (DateTimeParseException e) {
            throw new CollectionException(INPUT, "cases", line, field);
        }
    }

    static String binding(List<Case> cases, Config config) {
        ArrayNode bindings = JsonSupport.MAPPER.createArrayNode();
        cases.stream().sorted(Comparator.comparing(Case::caseId)).forEach(item -> {
            ObjectNode binding = bindings.addObject();
            binding.put("caseId", item.caseId());
            binding.put("expectedUserId", config.identities().get(item.identity()).expectedUserId());
            binding.set("request", item.request());
        });
        return JsonSupport.sha256(JsonSupport.encode(JsonSupport.canonical(bindings)));
    }
}
