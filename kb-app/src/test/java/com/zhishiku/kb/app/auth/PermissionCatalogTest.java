package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.model.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {
    @Test
    void catalogContainsTwentySixUniqueExactCodesWithDescriptions() {
        assertThat(Permission.codes()).hasSize(26).doesNotHaveDuplicates()
                .contains("document:security-level:write", "embedding:read", "embedding:rebuild",
                        "audit:read", "identity:external:read", "ai:usage:read", "ai:audit:read", "operations:reports:read",
                        "document:tag:read", "document:tag:write");
        for (Permission permission : Permission.values()) {
            assertThat(permission.code()).isNotBlank().doesNotContainPattern("\\s|\\*");
            assertThat(permission.description()).isNotBlank();
        }
        assertThat(Permission.contains("space:*")).isFalse();
    }
}
