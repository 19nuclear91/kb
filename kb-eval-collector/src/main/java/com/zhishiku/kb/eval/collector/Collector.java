package com.zhishiku.kb.eval.collector;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

final class Collector implements CollectionCli.Runner {
    private final Function<String, String> environment;
    private final Function<Inputs.Config, Transport> transports;
    private final LongSupplier monotonic;
    private final Clock utc;
    private final OutputPublisher publisher;

    Collector() {
        this(System::getenv, JdkTransport::new, System::nanoTime, Clock.systemUTC(), new OutputPublisher());
    }

    Collector(Function<String, String> environment, Function<Inputs.Config, Transport> transports,
              LongSupplier monotonic, Clock utc, OutputPublisher publisher) {
        this.environment = environment;
        this.transports = transports;
        this.monotonic = monotonic;
        this.utc = utc;
        this.publisher = publisher;
    }

    @Override
    public void run(CollectionCli.Options options) {
        Inputs.Batch batch = new Inputs().read(options.dataset(), options.cases(), options.config());
        Path output = publisher.preflight(options.output());
        Map<String, String> tokens = tokens(batch);
        Instant started = utc.instant();
        OutputPublisher.Session session = publisher.begin(output);
        try {
            // 所有本地校验和凭据冻结均已完成，随后才创建客户端。
            try (Transport transport = transports.apply(batch.config())) {
                Protocol protocol = new Protocol(transport, batch.config(), monotonic);
                for (String alias : tokens.keySet()) {
                    protocol.identity(tokens.get(alias), batch.config().identities().get(alias).expectedUserId());
                }
                for (Inputs.Case item : batch.cases()) {
                    OutputPublisher.checkInterrupted();
                    Protocol.SearchResult result = protocol.search(tokens.get(item.identity()), item);
                    session.append(item.caseId(), result);
                }
            }
            session.finish(batch, metadata(batch, tokens, started, utc.instant()));
        } finally {
            // 收尾失败优先返回本地输出错误，不能被远程错误的 suppressed 链掩盖。
            session.close();
        }
    }

    private Map<String, String> tokens(Inputs.Batch batch) {
        Map<String, String> tokens = new LinkedHashMap<>();
        Map<String, String> variables = new LinkedHashMap<>();
        for (Inputs.Case item : batch.cases()) {
            String env = batch.config().identities().get(item.identity()).tokenEnv();
            String token = variables.computeIfAbsent(env, environment);
            if (token == null || token.isBlank() || !token.chars().allMatch(c -> c >= 33 && c <= 126)) {
                throw new CollectionException(CollectionException.Code.CREDENTIAL, "identity", item.line(), "tokenEnv");
            }
            tokens.putIfAbsent(item.identity(), token);
        }
        return tokens;
    }

    private Map<String, Object> metadata(Inputs.Batch batch, Map<String, String> tokens, Instant started, Instant ended) {
        Inputs.Config config = batch.config();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("toolVersion", "0.1.0-SNAPSHOT");
        result.put("runId", config.runId());
        result.put("configurationId", config.configurationId());
        result.put("endpointId", config.endpointId());
        result.put("corpusSnapshotId", batch.corpusSnapshotId());
        result.put("startedAt", started.toString());
        result.put("endedAt", ended.toString());
        result.put("candidates", config.candidates());
        result.put("topK", config.topK());
        result.put("connectTimeoutMs", config.connectTimeoutMs());
        result.put("requestTimeoutMs", config.requestTimeoutMs());
        result.put("maxResponseBytes", config.maxResponseBytes());
        result.put("inputLimits", Map.of("manifestBytes", JsonSupport.MANIFEST_LIMIT, "jsonlBytes", JsonSupport.LINES_LIMIT,
                "lineBytes", JsonSupport.LINE_LIMIT, "maxCases", JsonSupport.CASE_LIMIT, "jsonDepth", 32,
                "requestBytes", JsonSupport.LINE_LIMIT, "queryCodePoints", 8000));
        result.put("caseCount", batch.cases().size());
        result.put("identities", tokens.keySet().stream().map(alias -> Map.of("alias", alias,
                "expectedUserId", config.identities().get(alias).expectedUserId(), "verification", "MATCHED")).toList());
        result.put("datasetSha256", batch.datasetSha256());
        result.put("labelsSha256", batch.labelsSha256());
        result.put("casesSha256", batch.casesSha256());
        result.put("scenarioBindingSha256", batch.scenarioBindingSha256());
        result.put("resultSemantics", "FINAL_RESPONSE");
        result.put("degradationStatus", "UNOBSERVED");
        result.put("snapshotVerification", "DECLARED_ONLY");
        return result;
    }
}
