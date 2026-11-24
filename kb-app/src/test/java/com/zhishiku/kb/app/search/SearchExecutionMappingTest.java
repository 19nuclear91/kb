package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.document.metadata.model.DocumentFileTypes;
import com.zhishiku.kb.app.search.model.SearchFilters;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 只检查实际参数绑定，不断言 SQL 文本或连接数据库。 */
class SearchExecutionMappingTest {
    @Test
    void expandedQueryAndCompleteRestrictionsBindExactlyIncludingDocumentExceptionKeys() throws Exception {
        var configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/SearchMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "search", configuration.getSqlFragments()).parse();
        }
        String query = "OA\n办公自动化";
        var filters = new SearchFilters(Long.MAX_VALUE, 4L, false, List.of(8L, 9L), SearchFilters.FileType.PDF,
                OffsetDateTime.parse("2026-10-01T00:00:00Z"), OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        var parameters = new HashMap<String, Object>();
        parameters.put("queryText", query);
        parameters.put("queryVector", null);
        parameters.put("spaceIds", List.of(3L));
        parameters.put("principalKeys", List.of("1:7"));
        parameters.put("clearance", (short) 2);
        parameters.put("candidates", 20);
        parameters.put("profileId", null);
        parameters.put("filters", filters);
        parameters.put("fileTypeRules", DocumentFileTypes.RULES);
        var mapped = configuration.getMappedStatement("com.zhishiku.kb.app.search.mapper.SearchMapper.filteredSearch");
        var bound = mapped.getBoundSql(parameters);
        var values = new HashMap<Integer, Object>();
        var statement = mock(PreparedStatement.class);
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(statement).setString(anyInt(), any());
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(statement).setLong(anyInt(), any(Long.class));
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(statement).setInt(anyInt(), anyInt());
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(statement).setObject(anyInt(), any());
        new DefaultParameterHandler(mapped, parameters, bound).setParameters(statement);
        assertThat(values.values()).contains(query, Long.MAX_VALUE, 4L, 8L, 9L, "1:7", 20, filters.updatedFrom(), filters.updatedTo());
        assertThat(values.values().stream().filter(query::equals).count()).isPositive();
    }
}
