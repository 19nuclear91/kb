package com.zhishiku.kb.app.document.changenote;

import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteCommandMapper;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeNoteMappingTest {
    @Test
    void allStatementsBindDeclaredParametersAndUseThreeSecondTimeouts() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        for (String resource : List.of("/mapper/changenote/ChangeNoteMapper.xml", "/mapper/changenote/ChangeNoteCommandMapper.xml")) {
            try (var input = getClass().getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        Map<String, List<String>> statements = new LinkedHashMap<>();
        String note = ChangeNoteMapper.class.getName() + ".";
        for (String id : List.of("target", "lockVersion", "get", "base")) {
            statements.put(note + id, List.of("documentId", "versionId"));
        }
        statements.put(note + "insert", List.of("versionId", "documentId", "text", "baseVersionId", "noteVersion", "corrected", "createdAfterVisible"));
        statements.put(note + "update", List.of("text", "baseVersionId", "noteVersion", "corrected", "createdAfterVisible", "versionId", "documentId"));
        statements.put(note + "appendHistory", List.of("versionId", "documentId", "textBefore", "textAfter", "baseBefore", "baseAfter", "visibleToReaders", "reason"));
        statements.put(note + "history", List.of("documentId", "versionId", "beforeTime", "beforeId", "limit"));
        statements.put(note + "hasPermission", List.of("documentId", "clearance"));
        String commands = ChangeNoteCommandMapper.class.getName() + ".";
        statements.put(commands + "lockRequest", List.of("key"));
        statements.put(commands + "get", List.of("scope", "requestId"));
        statements.put(commands + "insert", List.of("operationScope", "requestId", "actorUserId", "targetId", "documentId", "expectedVersion", "digestVersion", "inputDigest", "receipt"));
        Map<String, Object> values = new LinkedHashMap<>();
        statements.values().stream().flatMap(List::stream).distinct().forEach(key -> values.put(key, 1L));
        values.put("documentId", 9007199254740993L);
        values.put("versionId", Long.MAX_VALUE);
        values.put("beforeTime", OffsetDateTime.parse("2026-11-17T00:00:00Z"));
        values.put("aclPermission", "edit");
        values.put("spaceIds", List.of());
        values.put("principalKeys", List.of());
        for (var entry : statements.entrySet()) {
            var statement = configuration.getMappedStatement(entry.getKey());
            assertThat(statement.getTimeout()).isEqualTo(3);
            var sql = statement.getBoundSql(values);
            assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactlyElementsOf(entry.getValue());
            for (var mapping : sql.getParameterMappings()) {
                assertThat(configuration.newMetaObject(sql.getParameterObject()).getValue(mapping.getProperty()))
                        .isEqualTo(values.get(mapping.getProperty()));
            }
        }
    }
}
