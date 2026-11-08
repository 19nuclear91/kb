package com.zhishiku.kb.app.storage;

import com.zhishiku.kb.app.storage.mapper.StorageReconciliationMapper;
import com.zhishiku.kb.app.support.BoundValues;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证实际 MyBatis 生成 SQL 与参数，不运行数据库。 */
class StorageImportMapperTest {
    private static final String NS = StorageReconciliationMapper.class.getName() + ".";

    @Test
    void scanAndRecheckUnionAllActiveProtectionWithoutClockOrArchiveFilter() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/StorageReconciliationMapper.xml");
        var args = Map.of("objectKeys", List.of("documents/one", "documents/two"));
        var statement = configuration.getMappedStatement(NS + "listLiveObjectKeys");
        String sql = statement.getBoundSql(args).getSql();
        assertThat(sql).contains("UNION", "d.status <> 'deleted'", "kb_document_import_attempt", "a.state = 'ACTIVE'")
                .doesNotContain("lease_until", "archived", "current_version_id", "kb_document_import_item");
        assertThat(BoundValues.values(configuration, NS + "listLiveObjectKeys", args))
                .containsExactly("documents/one", "documents/two", "documents/one", "documents/two");
        assertThat(statement.isFlushCacheRequired()).isTrue();
        assertThat(statement.isUseCache()).isFalse();
        assertThat(configuration.getMappedStatement(NS + "hasLiveReference").getBoundSql(null).getSql())
                .contains("OR EXISTS", "kb_document_import_attempt", "state = 'ACTIVE'").doesNotContain("lease_until");
    }

    @Test
    void missingSampleOnlyIncludesExistingVersions() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/StorageReconciliationMapper.xml");
        var args = Map.of("limit", 100);
        assertThat(configuration.getMappedStatement(NS + "sampleLiveObjectKeys").getBoundSql(args).getSql())
                .contains("kb_document_version", "d.status <> 'deleted'", "ORDER BY v.id DESC")
                .doesNotContain("kb_document_import");
        assertThat(BoundValues.values(configuration, NS + "sampleLiveObjectKeys", args)).containsExactly(100);
    }
}
