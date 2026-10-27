package com.zhishiku.kb.common.embedding;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingProfileTest {

    static EmbeddingProfile profile() {
        return profile(Map.of());
    }

    private static EmbeddingProfile profile(Map<String, String> overrides) {
        Map<String, String> fields = new HashMap<>(Map.of(
                "id", "search-v1", "provider", "remote", "source", "publisher/model",
                "model", "model-v1", "revision", "revision-1", "query", "identity-v1",
                "document", "identity-v1", "queryTask", "query", "documentTask", "document",
                "adapter", "embeddings-v1"));
        fields.putAll(overrides);
        return new EmbeddingProfile(fields.get("id"), fields.get("provider"), fields.get("source"),
                fields.get("model"), fields.get("revision"), 1024, "cosine", fields.get("query"),
                fields.get("document"), fields.get("queryTask"), fields.get("documentTask"), fields.get("adapter"));
    }

    @Test
    void fingerprintIsDeterministicAndSemanticChangesAreIncompatible() {
        EmbeddingProfile original = profile();
        assertThat(original.fingerprint()).matches("[0-9a-f]{64}").isEqualTo(profile().fingerprint());
        for (String field : List.of("provider", "source", "model", "revision", "query", "document",
                "queryTask", "documentTask", "adapter")) {
            EmbeddingProfile changed = profile(Map.of(field, "different"));
            assertThat(original.fingerprint()).as(field).isNotEqualTo(changed.fingerprint());
            assertThat(original.compatibleWith(changed)).as(field).isFalse();
        }
        assertThat(original.compatibleWith(null)).isFalse();
    }

    @Test
    void aliasesHaveSameSemanticsButCannotBypassRegisteredIdentity() {
        EmbeddingProfile alias = profile(Map.of("id", "another-id"));
        assertThat(alias.fingerprint()).isEqualTo(profile().fingerprint());
        assertThat(alias.compatibleWith(profile())).isFalse();
        assertThat(profile().hasIdentity(profile().profileId(), profile().fingerprint())).isTrue();
    }

    @Test
    void pairedRolesMayUseDifferentTransforms() {
        EmbeddingProfile paired = profile(Map.of("query", "query-prefix-v1", "document", "document-prefix-v1"));
        assertThat(paired.transformVersion(EmbeddingRole.QUERY)).isEqualTo("query-prefix-v1");
        assertThat(paired.transformVersion(EmbeddingRole.DOCUMENT)).isEqualTo("document-prefix-v1");
        assertThat(paired.taskType(EmbeddingRole.QUERY)).isEqualTo("query");
        assertThat(paired.taskType(EmbeddingRole.DOCUMENT)).isEqualTo("document");
        assertThat(paired.compatibleWith(paired)).isTrue();
    }

    @Test
    void connectionSettingsCannotEnterProfileFingerprint() {
        /**
         * 用于验证连接参数不影响模型身份的部署测试数据。
         *
         * @param profile 不可变嵌入配置
         * @param endpoint 服务访问端点
         * @param apiKey 服务认证密钥，仅供内部调用，不得写入日志
         * @param timeout 测试部署的请求超时配置
         * @param batchSize 单批次处理数量
         */
        record Deployment(EmbeddingProfile profile, String endpoint, String apiKey, int timeout, int batchSize) {
        }
        Deployment before = new Deployment(profile(), "https://first.invalid", "old-key", 30, 16);
        Deployment after = new Deployment(profile(), "https://second.invalid", "new-key", 60, 32);
        assertThat(before.profile().fingerprint()).isEqualTo(after.profile().fingerprint());
        assertThat(Arrays.stream(EmbeddingProfile.class.getRecordComponents()).map(RecordComponent::getName))
                .doesNotContain("apiKey", "credentialRef", "endpoint", "timeout", "batchSize");
    }

    @Test
    void lengthPrefixesPreventDelimiterCollisions() {
        EmbeddingProfile first = profile(Map.of("source", "a|b", "model", "c"));
        EmbeddingProfile second = profile(Map.of("source", "a", "model", "b|c"));
        assertThat(first.fingerprint()).isNotEqualTo(second.fingerprint());
    }

    @Test
    void requiresExplicitWellFormedSemanticFieldsAndSupportedStorageShape() {
        for (String field : List.of("id", "provider", "source", "model", "revision", "query", "document",
                "queryTask", "documentTask", "adapter")) {
            for (String invalid : List.of("", " ", " padded ", "\ud800")) {
                assertThatThrownBy(() -> profile(Map.of(field, invalid)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        assertThatThrownBy(() -> new EmbeddingProfile("id", "remote", "source", "model", null,
                1024, "cosine", "identity-v1", "identity-v1", "query", "document", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProfile("id", "remote", "source", "model", "r1",
                768, "cosine", "identity-v1", "identity-v1", "query", "document", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProfile("id", "remote", "source", "model", "r1",
                1024, "l2", "identity-v1", "identity-v1", "query", "document", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
