package com.zhishiku.kb.app.knowledgegap;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 结构及基线检查，不执行迁移，不作为真实数据库约束生效证据。 */
class KnowledgeGapSchemaTest {
    @Test
    void legacyMigrationsRemainUnchanged() throws Exception {
        var baseline = new Properties();
        try (var input = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
                "/knowledgegap/legacy-migration-sha256.properties")), StandardCharsets.UTF_8)) {
            baseline.load(input);
        }
        assertThat(baseline).hasSize(35);
        for (String file : baseline.stringPropertyNames()) {
            String text = resource("/db/migration/" + file).replace("\r\n", "\n");
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
            assertThat(digest).as(file).isEqualTo(baseline.getProperty(file));
        }
    }

    @Test
    void currentSolutionAndQueueReferencesAreTypedAndScopedToSameGap() throws Exception {
        String sql = normalizedMigration();
        assertThat(sql).contains("foreign key (pending_solution_id, id, pending_solution_kind)",
                "references kb_knowledge_gap_entry (entry_id, gap_id, kind) deferrable initially deferred",
                "foreign key (queue_event_id, id, queue_event_is_queue)",
                "references kb_knowledge_gap_event (event_id, gap_id, is_queue) deferrable initially deferred",
                "check ((state = 'awaiting_confirmation') = (pending_solution_id is not null))",
                "check ((state = 'submitted') = (queue_event_id is not null))",
                "foreign key (version_id, document_id) references kb_document_version (id, doc_id)");
        assertThat(sql).doesNotContain("disable trigger", "drop table", "truncate ", "alter table kb_document_version");
    }

    @Test
    void factsAndReceiptsDoNotDuplicateBodiesOrPrivateSourceIdentities() throws Exception {
        String sql = normalizedMigration();
        for (String table : new String[]{"kb_knowledge_gap_event", "kb_knowledge_gap_event_recipient",
                "kb_knowledge_gap_command", "kb_knowledge_gap_actor_guard"}) {
            String definition = table(sql, table);
            assertThat(definition).doesNotContain("body ", "description ", "title ", "reason ",
                    "document_id", "version_id", "jsonb", "conversation_id", "turn_id", "search_id");
        }
        assertThat(table(sql, "kb_knowledge_gap_entry")).contains("body text not null",
                "unique (gap_id, position)", "check ((document_id is null) = (version_id is null))");
        assertThat(table(sql, "kb_knowledge_gap_command")).contains("request_id text primary key",
                "operation = 'create' and gap_id is null and expected_version is null");
    }

    @Test
    void originHistoryBindingsAndTerminalStatesAreProtected() throws Exception {
        String sql = normalizedMigration();
        assertThat(sql).contains("old.state in ('resolved', 'closed', 'withdrawn')",
                "new.version <> old.version + 1", "new.assignment_epoch <> old.assignment_epoch + 1",
                "old.result_event_id is not null or new.result_event_id is null");
        for (String table : new String[]{"kb_knowledge_gap_entry", "kb_knowledge_gap_event",
                "kb_knowledge_gap_event_recipient"}) {
            assertThat(sql).contains("before update or delete on " + table);
        }
        assertThat(sql).contains("before update or delete on kb_knowledge_gap_command",
                "before update or delete on kb_knowledge_gap ",
                "new.id, new.reporter_user_id, new.category, new.source, new.description, new.consent_version");
    }

    @Test
    void migrationGrantsOnlyExistingAdminOnceWithoutChangingResourceAcl() throws Exception {
        String sql = normalizedMigration();
        assertThat(sql).contains("insert into sys_role_permission (role_id, permission)",
                "('knowledge:gap:read')", "('knowledge:gap:assign')", "('knowledge:gap:handle')",
                "('knowledge:gap:close')", "where r.code = 'role_admin'",
                "on conflict (role_id, permission) do nothing");
        assertThat(sql).doesNotContain("insert into kb_space_acl", "insert into kb_document_acl",
                "update sys_user ", "delete from sys_role_permission");
    }

    private String normalizedMigration() throws Exception {
        return resource("/db/migration/V37__knowledge_gaps.sql").replaceAll("--[^\r\n]*", "")
                .replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private static String table(String sql, String name) {
        int start = sql.indexOf("create table " + name + " (");
        int end = sql.indexOf(");", start);
        assertThat(start).as(name).isNotNegative();
        assertThat(end).as(name).isGreaterThan(start);
        return sql.substring(start, end);
    }

    private String resource(String path) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream(path))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
