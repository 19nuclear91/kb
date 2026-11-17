package com.zhishiku.kb.answereval;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** 被测配置与一次冻结的凭据，未通过本地输入校验时不创建传输。 */
public record CollectionConfig(int schemaVersion, String runId, String targetId, String instanceId,
                               String artifactId, String configurationId, String model, String aclSnapshotId,
                               String baseUrl, boolean allowHttpLoopback, String captureDirectory,
                               Map<String, Identity> identities, Integer connectTimeoutMs, Integer caseTimeoutMs) {
    public record Identity(String tokenEnv, long expectedUserId) {
        public Identity {
            Checks.require(tokenEnv != null && tokenEnv.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") && expectedUserId > 0);
        }
    }

    public record Credentials(Map<String, String> tokens) {
        public Credentials {
            tokens = Map.copyOf(tokens);
        }

        @Override
        public String toString() {
            return "Credentials[redacted]";
        }
    }

    public CollectionConfig {
        Checks.require(schemaVersion == 1);
        Checks.id(runId);
        Checks.id(targetId);
        Checks.id(instanceId);
        Checks.id(artifactId);
        Checks.id(configurationId);
        Checks.id(aclSnapshotId);
        Checks.text(model, 255);
        Endpoint.validate(baseUrl, allowHttpLoopback);
        LocalFiles.path(captureDirectory);
        identities = Map.copyOf(identities);
        Checks.require(!identities.isEmpty());
        identities.keySet().forEach(Checks::id);
        connectTimeoutMs = connectTimeoutMs == null ? 5000 : connectTimeoutMs;
        caseTimeoutMs = caseTimeoutMs == null ? 180_000 : caseTimeoutMs;
        Checks.require(connectTimeoutMs > 0 && connectTimeoutMs <= 60_000
                && caseTimeoutMs >= connectTimeoutMs && caseTimeoutMs <= 600_000);
    }

    public URI endpoint() {
        return Endpoint.validate(baseUrl, allowHttpLoopback);
    }

    public Credentials credentials(DatasetReader.Batch batch, Function<String, String> environment) {
        batch.requireTarget(targetId, false);
        Map<String, String> values = new LinkedHashMap<>();
        for (DataModel.Case item : batch.cases()) {
            if (values.containsKey(item.identityAlias())) {
                continue;
            }
            Identity identity = identities.get(item.identityAlias());
            if (identity == null) {
                throw new EvaluationFailure(EvaluationFailure.Code.IDENTITY);
            }
            String token = environment.apply(identity.tokenEnv());
            if (token == null || token.isBlank() || token.length() > 8192
                    || !token.equals(token.strip()) || token.chars().anyMatch(c -> c < 33 || c > 126)) {
                throw new EvaluationFailure(EvaluationFailure.Code.IDENTITY);
            }
            values.put(item.identityAlias(), token);
        }
        return new Credentials(values);
    }

    @Override
    public String toString() {
        return "CollectionConfig[redacted]";
    }
}
