package com.zhishiku.kb.worker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Worker只检查app已迁移的订阅发布协议，不执行迁移或启动通知消费者。 */
@Component
@RequiredArgsConstructor
public class KnowledgePublicationSchemaCheck implements InitializingBean {
    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        Boolean schema = jdbc.queryForObject("""
                SELECT to_regclass('kb_knowledge_order') IS NOT NULL
                    AND to_regclass('kb_knowledge_publication') IS NOT NULL
                    AND to_regprocedure('kb_knowledge_next_order()') IS NOT NULL
                    AND to_regprocedure('kb_knowledge_capture_publication(uuid,bigint,timestamp with time zone)') IS NOT NULL
                """, Boolean.class);
        if (!Boolean.TRUE.equals(schema)) {
            throw new IllegalStateException("需先完成 app 迁移 V45，再启动 Worker");
        }
        Integer protocol = jdbc.queryForObject("SELECT protocol_version FROM kb_knowledge_order WHERE singleton", Integer.class);
        if (protocol == null || protocol != 1) {
            throw new IllegalStateException("知识订阅发布协议不兼容，需先升级 app");
        }
    }
}
