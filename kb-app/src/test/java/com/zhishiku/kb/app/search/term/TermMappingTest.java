package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class TermMappingTest {
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-24T09:00:00.123456+08:00");

    @Test
    void stateTermAliasAndCorrectionKeepExactIdsVersionsTimeAndIndependentFlags() throws Exception {
        Configuration config = configuration();
        var state = MapperRowStub.readOne(config, statement("state"), Map.of(),
                Map.of("revision", Long.MAX_VALUE, "database_time", TIME), TermRows.State.class);
        assertThat(state).isEqualTo(new TermRows.State(Long.MAX_VALUE, TIME));
        var termRow = new LinkedHashMap<String, Object>();
        termRow.put("id", Long.MAX_VALUE);
        termRow.put("canonical_name", "OA");
        termRow.put("canonical_key", "oa");
        termRow.put("description", "释义");
        termRow.put("enabled", false);
        termRow.put("version", Long.MAX_VALUE);
        termRow.put("created_at", TIME);
        termRow.put("updated_at", TIME.plusNanos(1000));
        assertThat(MapperRowStub.readOne(config, statement("term"), Map.of("id", Long.MAX_VALUE), termRow, TermRows.Term.class))
                .isEqualTo(new TermRows.Term(Long.MAX_VALUE, "OA", "oa", "释义", false, Long.MAX_VALUE, TIME, TIME.plusNanos(1000)));
        var alias = MapperRowStub.readOne(config, statement("aliases"), Map.of("id", Long.MAX_VALUE),
                Map.of("term_id", Long.MAX_VALUE, "alias_text", "OA", "lookup_key", "oa", "kind", "ABBREVIATION"), TermRows.Alias.class);
        assertThat(alias).isEqualTo(new TermRows.Alias(Long.MAX_VALUE, "OA", "oa", TermRows.AliasKind.ABBREVIATION));
        var wrong = new LinkedHashMap<String, Object>();
        wrong.put("id", 9007199254740993L);
        wrong.put("wrong_expression", "报消");
        wrong.put("wrong_key", "报消");
        wrong.put("target_term_id", Long.MAX_VALUE);
        wrong.put("enabled", true);
        wrong.put("version", 0L);
        wrong.put("created_at", TIME);
        wrong.put("updated_at", TIME);
        assertThat(MapperRowStub.readOne(config, statement("correction"), Map.of("id", 9007199254740993L), wrong, TermRows.Correction.class))
                .isEqualTo(new TermRows.Correction(9007199254740993L, "报消", "报消", Long.MAX_VALUE, true, 0, TIME, TIME));
    }

    @Test
    void createReceiptPreservesNullableIntentTargetAndVersionAndExactOperator() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("request_id", "request");
        row.put("operation", "SEARCH_TERM_CREATE");
        row.put("actor_user_id", Long.MAX_VALUE);
        row.put("target_kind", "TERM");
        row.put("target_id", null);
        row.put("expected_version", null);
        row.put("digest_version", 1);
        row.put("input_digest", "a".repeat(64));
        row.put("receipt", "{}");
        row.put("created_at", TIME);
        var mapped = MapperRowStub.readOne(configuration(), statement("command"), Map.of("requestId", "request"), row, TermRows.Command.class);
        assertThat(mapped).isEqualTo(new TermRows.Command("request", TermRows.Operation.SEARCH_TERM_CREATE,
                Long.MAX_VALUE, TermRows.EntityKind.TERM, null, null, 1, "a".repeat(64), "{}", TIME));
        row.put("operation", "SEARCH_TERM_UPDATE");
        row.put("target_id", Long.MAX_VALUE);
        row.put("expected_version", Long.MAX_VALUE);
        mapped = MapperRowStub.readOne(configuration(), statement("command"), Map.of("requestId", "request"), row, TermRows.Command.class);
        assertThat(mapped.targetId()).isEqualTo(Long.MAX_VALUE);
        assertThat(mapped.expectedVersion()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void parameterMappingsUseExactValuesAndAllBaseOperationsHaveFiniteTimeoutsWithoutCaching() {
        Configuration config = configuration();
        Map<String, Object> params = Map.of("id", Long.MAX_VALUE, "requestId", "request", "key", "domain:request");
        for (String name : List.of("manageTimeouts", "state", "lockState", "lockRequest", "term", "aliases", "correction", "command")) {
            var mapped = config.getMappedStatement(statement(name));
            assertThat(mapped.getTimeout()).isEqualTo(5);
            assertThat(mapped.isUseCache()).isFalse();
            var bound = mapped.getBoundSql(params);
            List<String> expected = switch (name) {
                case "term", "aliases", "correction" -> List.of("id");
                case "command" -> List.of("requestId");
                case "lockRequest" -> List.of("key");
                default -> List.of();
            };
            assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactlyElementsOf(expected);
            for (var mapping : bound.getParameterMappings()) {
                assertThat(config.newMetaObject(bound.getParameterObject()).getValue(mapping.getProperty())).isEqualTo(params.get(mapping.getProperty()));
            }
        }
    }

    private static Configuration configuration() {
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TermMapper.class);
        return config;
    }

    @Test
    void appendCommandBindsCompleteNullableIntentAndExactReceiptWithoutConnection() {
        Configuration config = configuration();
        var row = new TermRows.Command("request", TermRows.Operation.SEARCH_TERM_CREATE, Long.MAX_VALUE,
                TermRows.EntityKind.TERM, null, null, 1, "a".repeat(64), "{}", TIME);
        var mapped = config.getMappedStatement(statement("insertCommand"));
        assertThat(mapped.getTimeout()).isEqualTo(5);
        var bound = mapped.getBoundSql(row);
        List<String> fields = List.of("requestId", "operation", "actorUserId", "targetKind", "targetId", "expectedVersion", "digestVersion", "inputDigest", "receipt");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactlyElementsOf(fields);
        var meta = config.newMetaObject(bound.getParameterObject());
        assertThat(meta.getValue("actorUserId")).isEqualTo(Long.MAX_VALUE);
        assertThat(meta.getValue("targetId")).isNull();
        assertThat(meta.getValue("expectedVersion")).isNull();
        assertThat(meta.getValue("operation")).isEqualTo(TermRows.Operation.SEARCH_TERM_CREATE);
        assertThat(meta.getValue("receipt")).isEqualTo(row.receipt());
    }

    private static String statement(String method) {
        return TermMapper.class.getName() + "." + method;
    }

    @Test
    void termAndCorrectionWritesBindFullNormalizedContentAndIndependentEnabledParameters() {
        Configuration config = configuration();
        var term = new TermInputs.TermWrite("OA", "oa", "释义", List.of(), Long.MAX_VALUE);
        var correction = new TermInputs.CorrectionWrite("报消", "报消", Long.MAX_VALUE, Long.MAX_VALUE);
        var enabled = new TermInputs.EnabledWrite(false, Long.MAX_VALUE);
        var alias = new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION);
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("insertTerm", List.of("canonicalName", "canonicalKey", "description"));
        fields.put("updateTerm", List.of("input.canonicalName", "input.canonicalKey", "input.description", "id", "input.expectedVersion"));
        fields.put("enableTerm", List.of("input.enabled", "id", "input.expectedVersion"));
        fields.put("insertAlias", List.of("id", "alias.text", "alias.key", "alias.kind"));
        fields.put("clearAliases", List.of("id"));
        fields.put("advanceRevision", List.of("revision"));
        fields.put("insertCorrection", List.of("wrongExpression", "wrongKey", "termId"));
        fields.put("updateCorrection", List.of("input.wrongExpression", "input.wrongKey", "input.termId", "id", "input.expectedVersion"));
        fields.put("enableCorrection", List.of("input.enabled", "id", "input.expectedVersion"));
        for (var entry : fields.entrySet()) {
            String method = entry.getKey();
            Object parameter = switch (method) {
                case "insertTerm" -> term;
                case "insertCorrection" -> correction;
                default -> Map.of("id", Long.MAX_VALUE, "revision", Long.MAX_VALUE, "alias", alias,
                        "input", method.startsWith("enable") ? enabled : method.contains("Correction") ? correction : term);
            };
            var mapped = config.getMappedStatement(statement(method));
            assertThat(mapped.getTimeout()).isEqualTo(5);
            var bound = mapped.getBoundSql(parameter);
            assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactlyElementsOf(entry.getValue());
            var meta = config.newMetaObject(bound.getParameterObject());
            for (String field : entry.getValue()) {
                assertThat(meta.getValue(field)).isNotNull();
                if (field.equals("id") || field.equals("revision") || field.equals("input.expectedVersion") || field.equals("termId") || field.equals("input.termId")) {
                    assertThat(meta.getValue(field)).isEqualTo(Long.MAX_VALUE);
                }
            }
        }
    }

    @Test
    void directoriesBindLiteralPatternsStateAndPrecisePositionsOrOnlyDefaultFetch() {
        Configuration config = configuration();
        Map<String, Object> filtered = Map.of("namePattern", "%\\%\\_\\\\%", "enabled", false, "afterId", Long.MAX_VALUE, "fetch", 101);
        for (String method : List.of("terms", "corrections")) {
            var mapped = config.getMappedStatement(statement(method));
            assertThat(mapped.getTimeout()).isEqualTo(5);
            var bound = mapped.getBoundSql(filtered);
            List<String> fields = method.equals("terms") ? List.of("namePattern", "namePattern", "enabled", "afterId", "fetch")
                    : List.of("namePattern", "enabled", "afterId", "fetch");
            assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactlyElementsOf(fields);
            assertThat(config.newMetaObject(bound.getParameterObject()).getValue("afterId")).isEqualTo(Long.MAX_VALUE);
            assertThat(config.newMetaObject(bound.getParameterObject()).getValue("namePattern")).isEqualTo("%\\%\\_\\\\%");
            var defaults = new LinkedHashMap<String, Object>();
            defaults.put("namePattern", null);
            defaults.put("enabled", null);
            defaults.put("afterId", null);
            defaults.put("fetch", 21);
            assertThat(mapped.getBoundSql(defaults).getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("fetch");
        }
    }

    @Test
    void sharedMeaningsAndBatchAmbiguityBindOnlyExactKeysAndBoundedPositions() throws Exception {
        Configuration config = configuration();
        var meanings = config.getMappedStatement(statement("meanings"));
        var bound = meanings.getBoundSql(Map.of("key", "pm", "afterId", Long.MAX_VALUE, "fetch", 101));
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("key", "key", "afterId", "fetch");
        assertThat(meanings.getTimeout()).isEqualTo(5);
        List<String> keys = List.of("项目管理", "pm", "产品经理");
        var associations = config.getMappedStatement(statement("ambiguities"));
        var associationBound = associations.getBoundSql(Map.of("keys", keys));
        assertThat(associations.getTimeout()).isEqualTo(5);
        assertThat(associationBound.getParameterMappings()).hasSize(keys.size());
        List<String> maximumKeys = LongStream.rangeClosed(1, 2100).mapToObj(id -> "key" + id).toList();
        assertThat(associations.getBoundSql(Map.of("keys", maximumKeys)).getParameterMappings()).hasSize(2100);
        for (int i = 0; i < keys.size(); i++) {
            String property = associationBound.getParameterMappings().get(i).getProperty();
            assertThat(associationBound.getAdditionalParameter(property)).isEqualTo(keys.get(i));
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("lookup_key", "pm");
        row.put("ambiguous", true);
        assertThat(MapperRowStub.readOne(config, statement("ambiguities"), Map.of("keys", keys), row, TermRows.Expression.class))
                .isEqualTo(new TermRows.Expression("pm", true));
        row.put("ambiguous", null);
        assertThat(MapperRowStub.readOne(config, statement("ambiguities"), Map.of("keys", keys), row, TermRows.Expression.class).ambiguous()).isNull();
    }
}
