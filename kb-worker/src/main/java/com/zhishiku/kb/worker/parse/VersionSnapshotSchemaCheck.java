package com.zhishiku.kb.worker.parse;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 创建解析调度器前确认 app 已完成结构快照迁移。 */
@Component
public class VersionSnapshotSchemaCheck implements InitializingBean {
    private final JdbcTemplate jdbc;

    public VersionSnapshotSchemaCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        for (String table : List.of("kb_document_version_snapshot", "kb_document_version_block")) {
            Boolean available = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table);
            if (!Boolean.TRUE.equals(available)) {
                throw new IllegalStateException("需先完成 app 迁移 V40，再启动 Worker");
            }
        }
    }
}
