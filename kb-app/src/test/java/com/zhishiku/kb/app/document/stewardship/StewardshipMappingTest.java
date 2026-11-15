package com.zhishiku.kb.app.document.stewardship;

import com.zhishiku.kb.app.document.applicability.ValidityBoundaryCases;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StewardshipMappingTest {
    static Configuration configuration() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/DocumentMapper.xml");
        for (String resource : List.of("/mapper/DocumentReviewEligibilityMapper.xml", "/mapper/stewardship/StewardshipMapper.xml")) {
            try (var input = StewardshipMappingTest.class.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Test
    void allStatementsAreBoundedAndLockDocumentBeforeStewardshipWithExplicitCandidateProjection() throws Exception {
        var configuration = configuration();
        String namespace = StewardshipMapper.class.getName();
        for (String name : List.of("document", "shareDocument", "get", "lock", "insert", "update", "responsible", "eligible", "candidates", "mine", "count", "space")) {
            assertThat(configuration.getMappedStatement(namespace + "." + name).getTimeout()).isEqualTo(3);
        }
        assertThat(configuration.getMappedStatement(namespace + ".shareDocument").getBoundSql(Map.of("documentId", 10)).getSql()).contains("FOR SHARE OF d");
        assertThat(configuration.getMappedStatement(namespace + ".lock").getBoundSql(Map.of("documentId", 10)).getSql()).contains("FOR UPDATE");
        var statement = configuration.getMappedStatement(namespace + ".candidates");
        var sql = statement.getBoundSql(Map.of("documentId", 10, "namePattern", "%private-name%", "afterId", 7, "limit", 21));
        assertThat(sql.getSql()).contains("a.permission = 'view'", "a.permission = 'edit'", "u.must_change_password = FALSE")
                .doesNotContain("private-name", "SELECT *");
        assertThat(statement.getResultMaps().getFirst().getConstructorResultMappings()).extracting(mapping -> mapping.getColumn())
                .containsExactly("user_id", "display_name");
        assertThat(sql.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("documentId", "namePattern", "afterId", "limit");
        assertThat(configuration.getSqlFragments()).containsKey(namespace + ".stewardEligible");
    }

    @Test
    void mineAndCountExpandExactlySameWhereAndBindSharedBoundaryDates() throws Exception {
        var configuration = configuration();
        var parameters = new HashMap<String, Object>();
        parameters.put("actor", 7L);
        parameters.put("view", "attention");
        parameters.put("spaceIds", List.of(2L));
        parameters.put("principalKeys", List.of("1:7"));
        parameters.put("clearance", (short) 2);
        parameters.put("limit", 21);
        for (var row : ValidityBoundaryCases.cases("applicability").toList()) {
            parameters.put("today", LocalDate.parse(row.get(1)));
            var mine = configuration.getMappedStatement(StewardshipMapper.class.getName() + ".mine").getBoundSql(parameters);
            var count = configuration.getMappedStatement(StewardshipMapper.class.getName() + ".count").getBoundSql(parameters);
            String mineSql = mine.getSql().replaceAll("\\s+", " ");
            String countSql = count.getSql().replaceAll("\\s+", " ");
            assertThat(mineSql.substring(mineSql.indexOf("WHERE f.responsible_user_id"), mineSql.indexOf("ORDER BY f.due_date")))
                    .isEqualTo(countSql.substring(countSql.indexOf("WHERE f.responsible_user_id")) + " ");
            assertThat(mineSql).contains("a.effective_from IS NULL OR a.effective_from <= ?");
            assertThat(count.getParameterMappings()).extracting(mapping -> mapping.getProperty()).contains("today", "actor", "clearance");
        }
    }
}
