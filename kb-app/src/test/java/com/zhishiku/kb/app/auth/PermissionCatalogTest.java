package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.model.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {
    @Test
    void catalogContainsTwentyFourUniqueExactCodesWithDescriptions() {
        assertThat(Permission.codes()).hasSize(24).doesNotHaveDuplicates()
                .contains("document:security-level:write", "embedding:read", "embedding:rebuild",
                        "audit:read", "identity:external:read", "ai:usage:read", "ai:audit:read", "operations:reports:read");
        for (Permission permission : Permission.values()) {
            assertThat(permission.code()).isNotBlank().doesNotContainPattern("\\s|\\*");
            assertThat(permission.description()).isNotBlank();
        }
        assertThat(Permission.contains("space:*")).isFalse();
    }
}
