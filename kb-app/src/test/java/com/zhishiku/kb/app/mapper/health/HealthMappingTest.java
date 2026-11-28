package com.zhishiku.kb.app.mapper.health;

import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthSelection;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HealthMappingTest {
    private Configuration configuration() throws Exception {
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        for (String resource : List.of("mapper/AuthorizationRules.xml", "mapper/DocumentMapper.xml",
                "mapper/DocumentReviewEligibilityMapper.xml", "mapper/stewardship/StewardshipMapper.xml", "mapper/health/HealthMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        return config;
    }

    @Test
    void everyFilterBindsParametersWithoutConnectingOrAssertingSqlText() throws Exception {
        var config = configuration();
        var signals = new ArrayList<>(List.of("ALL"));
        for (var signal : HealthSignal.values()) {
            signals.add(signal.name());
        }
        for (String signal : signals) {
            for (List<Long> spaces : List.of(List.<Long>of(), List.of(2L))) {
                var params = new HealthSelection(2, LocalDate.of(2026, 11, 27), spaces, List.of("1:7", "3:2"), (short) 2, signal, 4L, 21);
                for (String method : List.of("totals", "page")) {
                    var statement = config.getMappedStatement(HealthMapper.class.getName() + "." + method);
                    var bound = statement.getBoundSql(params);
                    new DefaultParameterHandler(statement, params, bound).setParameters(mock(PreparedStatement.class));
                    assertThat(statement.getTimeout()).isEqualTo(3);
                    assertThat(statement.isUseCache()).isFalse();
                    assertThat(bound.getParameterObject()).isSameAs(params);
                }
            }
        }
    }

    @Test
    void actualResultMappingRetainsNullableHiddenVersionAndExactIds() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("document_id", Long.MAX_VALUE);
        row.put("responsible_user_id", 7L);
        row.put("display_name", "负责人");
        row.put("due_date", LocalDate.of(2026, 11, 1));
        row.put("eligible", true);
        row.put("stewardship_state", "OVERDUE");
        row.put("version_id", null);
        row.put("effective_from", null);
        row.put("until_kind", null);
        row.put("effective_until", null);
        row.put("applicability_state", null);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("spaceId", 2L);
        parameters.put("today", LocalDate.of(2026, 11, 27));
        parameters.put("spaceIds", List.of());
        parameters.put("principalKeys", List.of("1:7"));
        parameters.put("clearance", (short) 1);
        parameters.put("signal", "ALL");
        parameters.put("afterId", null);
        parameters.put("limit", 21);
        var mapped = MapperRowStub.readOne(configuration(), HealthMapper.class.getName() + ".page", parameters, row, HealthRow.class);
        assertThat(mapped.documentId()).isEqualTo(Long.MAX_VALUE);
        assertThat(mapped.versionId()).isNull();
        assertThat(mapped.untilKind()).isNull();
        assertThat(mapped.dueDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }
}
