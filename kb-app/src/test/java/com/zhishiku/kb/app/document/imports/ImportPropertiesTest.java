package com.zhishiku.kb.app.document.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportPropertiesTest {
    @Test
    void bindsDefaultsWithoutApplicationOrExternalConnections() {
        var properties = bind(Map.of());
        assertThat(properties.itemLimit()).isEqualTo(100);
        assertThat(properties.totalLimit()).isEqualTo(1073741824L);
        assertThat(properties.capacity()).isEqualTo(8);
        assertThat(properties.userCapacity()).isEqualTo(2);
        assertThat(properties.attemptSeconds()).isEqualTo(120);
        assertThat(properties.storageSeconds()).isEqualTo(60);
        assertThat(properties.recoveryLimit()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidIntegersAndBudgetRelationships() {
        for (Object value : new Object[]{0, -1, 1.5, "1.0", "9223372036854775808", 501}) {
            assertThatThrownBy(() -> bind(Map.of("kb.document-import.max-items", value)))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> bind(Map.of("kb.document-import.max-concurrent", 1)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("kb.document-import.attempt-timeout-seconds", 50)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bind(Map.of("kb.document-import.attempt-timeout-seconds", 121)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesExistingFileLimitWithoutStorageSecrets() {
        var value = bind(Map.of("kb.document-import.max-concurrent-per-user", 1));
        var storage = new StorageProperties("secret-endpoint", "secret-region", "secret-access", "secret-key", "secret-bucket", true, 1234);
        var tree = new ObjectMapper().valueToTree(value.capabilities(storage));
        assertThat(tree.get("maxFileBytes").longValue()).isEqualTo(1234);
        assertThat(tree.get("uploadConcurrency").intValue()).isEqualTo(1);
        assertThat(tree.toString()).doesNotContain("secret-");
        assertThat(tree.size()).isEqualTo(7);
    }

    @Test
    void storageBudgetFitsLeaseAndRecoveryRemainsBounded() {
        var equal = bind(Map.of("kb.document-import.attempt-timeout-seconds", 30,
                "kb.document-import.storage-timeout-seconds", 30));
        assertThat(equal.storageSeconds()).isEqualTo(equal.attemptSeconds());
        for (var value : Map.of("storage-timeout-seconds", "121", "recovery-interval-ms", "999",
                "recovery-batch-size", "1001", "max-concurrent-per-user", "9").entrySet()) {
            assertThatThrownBy(() -> bind(Map.of("kb.document-import." + value.getKey(), value.getValue())))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    private ImportProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("kb.document-import", Bindable.of(ImportProperties.class));
    }
}
