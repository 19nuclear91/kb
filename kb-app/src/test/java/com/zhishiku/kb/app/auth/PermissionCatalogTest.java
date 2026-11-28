package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.model.Permission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {
    @Test
    void catalogContainsFortyUniqueExactCodes() {
        assertThat(Permission.codes()).hasSize(40).doesNotHaveDuplicates()
                .contains("document:security-level:write", "embedding:read", "embedding:rebuild",
                        "audit:read", "identity:external:read", "ai:usage:read", "ai:audit:read", "operations:reports:read",
                        "document:tag:read", "document:tag:write", "identity:lifecycle:read",
                        "identity:lifecycle:manage", "identity:lifecycle:restore", "identity:emergency:manage",
                        "knowledge:gap:read", "knowledge:gap:assign", "knowledge:gap:handle", "knowledge:gap:close",
                        "search:term:read", "search:term:write", "authorization:diagnose",
                        "answer:card:write", "answer:card:review", "knowledge:health:read");
        for (Permission permission : Permission.values()) {
            assertThat(permission.code()).isNotBlank().doesNotContainPattern("\\s|\\*");
        }
        assertThat(Permission.contains("space:*")).isFalse();
        assertThat(Permission.contains("knowledge:gap:*")).isFalse();
        assertThat(Permission.contains("knowledge:gap")).isFalse();
        assertThat(Permission.contains("KNOWLEDGE:GAP:READ")).isFalse();
        assertThat(Permission.contains("search:term:*")).isFalse();
        assertThat(Permission.contains("answer:card:*")).isFalse();
    }
}
