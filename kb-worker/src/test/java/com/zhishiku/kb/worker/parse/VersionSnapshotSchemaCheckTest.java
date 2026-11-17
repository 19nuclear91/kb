package com.zhishiku.kb.worker.parse;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VersionSnapshotSchemaCheckTest {
    @Test
    void startsOnlyWhenBothTablesExist() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
        assertThatCode(new VersionSnapshotSchemaCheck(jdbc)::afterPropertiesSet).doesNotThrowAnyException();
        for (String missing : List.of("kb_document_version_snapshot", "kb_document_version_block")) {
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(true);
            when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(missing))).thenReturn(false);
            assertThatThrownBy(new VersionSnapshotSchemaCheck(jdbc)::afterPropertiesSet)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("先完成 app 迁移");
        }
    }
}
