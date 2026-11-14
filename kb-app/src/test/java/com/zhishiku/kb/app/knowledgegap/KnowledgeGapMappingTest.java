package com.zhishiku.kb.app.knowledgegap;

import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MyBatis 映射和替身 JDBC 结果集，不连接数据库或执行 SQL。 */
class KnowledgeGapMappingTest {
    private static final String NAMESPACE = KnowledgeGapMapper.class.getName() + ".";

    @Test
    void mapsNullableWorkflowAndExactLargeIdentities() throws Exception {
        var configuration = configuration();
        UUID queue = UUID.randomUUID();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", 9007199254740993L);
        row.put("reporter_user_id", 9007199254740994L);
        row.put("category", "OUTDATED");
        row.put("source", "SEARCH");
        row.put("description", "明确分享的问题");
        row.put("consent_version", "GAP_SHARE_V1");
        row.put("state", "SUBMITTED");
        row.put("version", 0L);
        row.put("assignee_user_id", null);
        row.put("assignment_epoch", 0L);
        row.put("pending_solution_id", null);
        row.put("queue_event_id", queue);
        row.put("created_at", Instant.EPOCH);
        row.put("updated_at", Instant.EPOCH);
        var result = MapperRowStub.readOne(configuration, NAMESPACE + "findVisible",
                Map.of("gapId", 9007199254740993L, "actor", 9007199254740994L), row, KnowledgeGapRows.Gap.class);
        assertThat(result.id()).isEqualTo(9007199254740993L);
        assertThat(result.reporterUserId()).isEqualTo(9007199254740994L);
        assertThat(result.workflow().queueEventId()).isEqualTo(queue);
        assertThat(result.assigneeUserId()).isNull();
        assertThat(result.createdAt()).isEqualTo(Instant.EPOCH);
        assertThat(result.toString()).doesNotContain(result.description());
    }

    @Test
    void mapsEntryAndEventIndependentlyFromBodies() throws Exception {
        var configuration = configuration();
        UUID id = UUID.randomUUID();
        var entry = new LinkedHashMap<String, Object>();
        entry.put("entry_id", id);
        entry.put("gap_id", 11L);
        entry.put("position", 2L);
        entry.put("kind", "SOLUTION");
        entry.put("author_user_id", 7L);
        entry.put("body", "共享方案");
        entry.put("document_id", null);
        entry.put("version_id", null);
        entry.put("created_at", Instant.EPOCH);
        var result = MapperRowStub.readOne(configuration, NAMESPACE + "entry",
                Map.of("gapId", 11L, "entryId", id, "actor", 7L), entry, KnowledgeGapRows.Entry.class);
        assertThat(result.entryId()).isEqualTo(id);
        assertThat(result.documentId()).isNull();
        assertThat(result.body()).isEqualTo("共享方案");
        var event = new LinkedHashMap<String, Object>();
        event.put("event_id", UUID.randomUUID());
        event.put("gap_id", 11L);
        event.put("kind", "GAP_SOLUTION_SUBMITTED");
        event.put("result_version", 2L);
        event.put("actor_user_id", 7L);
        event.put("assignee_user_id", 7L);
        event.put("assignment_epoch", 1L);
        event.put("entry_id", id);
        event.put("solution_id", id);
        event.put("occurred_at", Instant.EPOCH);
        var mapped = MapperRowStub.readOne(configuration, NAMESPACE + "history",
                historyParameters(), event, KnowledgeGapRows.Event.class);
        assertThat(mapped.solutionId()).isEqualTo(id);
        assertThat(mapped.assignmentEpoch()).isEqualTo(1);
    }

    @Test
    void mapsCreateBindingWithNullTargetAndMinimalCompletedReceipt() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("request_id", "request-1");
        row.put("actor_user_id", 7L);
        row.put("operation", "CREATE");
        row.put("gap_id", null);
        row.put("expected_version", null);
        row.put("input_digest", "a".repeat(64));
        row.put("result_gap_id", 11L);
        row.put("result_version", 0L);
        row.put("result_state", "SUBMITTED");
        row.put("result_event_id", UUID.randomUUID());
        row.put("result_solution_id", null);
        var mapped = MapperRowStub.readOne(configuration(), NAMESPACE + "command",
                Map.of("requestId", "request-1"), row, KnowledgeGapRows.Command.class);
        assertThat(mapped.operation()).isEqualTo(KnowledgeGapAction.CREATE);
        assertThat(mapped.gapId()).isNull();
        assertThat(mapped.expectedVersion()).isNull();
        assertThat(mapped.resultGapId()).isEqualTo(11);
        assertThat(mapped.resultSolutionId()).isNull();
        assertThat(sql(configuration(), "command", Map.of("requestId", "request-1")))
                .doesNotContain("body", "description", "document_id", "version_id");
    }

    @Test
    void bindingsTimeoutsLocksAndPaginationRemainBoundedAndUncached() throws Exception {
        var configuration = configuration();
        for (var method : KnowledgeGapMapper.class.getDeclaredMethods()) {
            var statement = configuration.getMappedStatement(NAMESPACE + method.getName());
            assertThat(statement.getTimeout()).as(method.getName()).isEqualTo(3);
            assertThat(statement.isUseCache()).as(method.getName()).isFalse();
        }
        var listParameters = new LinkedHashMap<String, Object>();
        listParameters.put("actor", 7L);
        listParameters.put("view", "assigned");
        listParameters.put("status", KnowledgeGapState.IN_PROGRESS);
        listParameters.put("beforeId", 12L);
        listParameters.put("limit", 21);
        String list = sql(configuration, "list", listParameters);
        assertThat(list).contains("not ga_u.must_change_password", "ga_d.enabled",
                "ga_rp.permission = 'knowledge:gap:handle'", "g.state = ?", "g.id < ?",
                "order by g.id desc limit ?");
        assertThat(list.indexOf("knowledge:gap:handle")).isLessThan(list.indexOf("order by"));
        assertThat(sql(configuration, "lock", Map.of("gapId", 11L, "actor", 7L))).endsWith("for update of g");
        assertThat(sql(configuration, "configureLockTimeout", Map.of())).contains("'lock_timeout', '1s', true");
        assertThat(sql(configuration, "lockActorGuard", Map.of("actor", 7L))).endsWith("for update");
        assertThat(sql(configuration, "history", historyParameters())).contains("e.result_version > ?",
                "order by e.result_version asc limit ?");
        var binding = new KnowledgeGapRows.Binding("request-1", 7L, KnowledgeGapAction.CREATE, null, null, "a".repeat(64));
        var bound = configuration.getMappedStatement(NAMESPACE + "claim").getBoundSql(Map.of("binding", binding));
        assertThat(bound.getParameterMappings()).extracting("property").containsExactly("binding.requestId",
                "binding.actorUserId", "binding.operation", "binding.gapId", "binding.expectedVersion", "binding.inputDigest");
        assertThat(bound.getSql()).contains("ON CONFLICT (request_id) DO NOTHING");
        var receipt = new KnowledgeGapRows.Receipt(11, 0, KnowledgeGapState.SUBMITTED, UUID.randomUUID(), null, false);
        assertThat(sql(configuration, "complete", Map.of("requestId", "request-1", "receipt", receipt)))
                .contains("result_event_id is null").doesNotContain("body", "description", "document_id");
        assertThat(sql(configuration, "countOpen", Map.of("actor", 7L)))
                .contains("'submitted', 'in_progress', 'awaiting_confirmation'");
    }

    private static Map<String, Object> historyParameters() {
        return Map.of("gapId", 11L, "actor", 7L, "afterVersion", 0L, "limit", 21);
    }

    private static Configuration configuration() throws Exception {
        return MapperRowStub.configuration("/mapper/KnowledgeGapMapper.xml");
    }

    private static String sql(Configuration configuration, String id, Map<String, Object> parameters) {
        return configuration.getMappedStatement(NAMESPACE + id).getBoundSql(parameters).getSql()
                .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
