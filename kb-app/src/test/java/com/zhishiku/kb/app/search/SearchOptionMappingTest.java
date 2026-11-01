package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用生产结果映射和内存结果行，不执行查询。 */
class SearchOptionMappingTest {
    @Test
    void sharedMapperFragmentsResolveAndCategoryPathProjectionKeepsExactIds() throws Exception {
        var configuration = configuration();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", Long.MAX_VALUE);
        row.put("parent_id", null);
        row.put("name", "分类");
        row.put("sort_order", 0);
        var category = MapperRowStub.readOne(configuration,
                "com.zhishiku.kb.app.search.mapper.SearchOptionMapper.categories", parameters(), row, SearchOptions.CategoryRow.class);
        assertThat(category).isEqualTo(new SearchOptions.CategoryRow(Long.MAX_VALUE, null, "分类", 0));
        var pathRow = new LinkedHashMap<String, Object>();
        pathRow.put("leaf_id", Long.MAX_VALUE);
        pathRow.put("id", 2L);
        pathRow.put("parent_id", 1L);
        pathRow.put("name", "祖先");
        pathRow.put("depth", 2);
        var path = MapperRowStub.readOne(configuration,
                "com.zhishiku.kb.app.search.mapper.SearchOptionMapper.paths", Map.of("spaceId", 1L, "ids", List.of(Long.MAX_VALUE)),
                pathRow, SearchOptions.PathRow.class);
        assertThat(path).isEqualTo(new SearchOptions.PathRow(Long.MAX_VALUE, 2, 1L, "祖先", 2));
    }

    @Test
    void tagProjectionKeepsDisabledStateWithoutManagementFields() throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", Long.MAX_VALUE);
        row.put("name", "停用");
        row.put("enabled", false);
        var tag = MapperRowStub.readOne(configuration(),
                "com.zhishiku.kb.app.search.mapper.SearchOptionMapper.tags", parameters(),
                row, SearchOptions.Tag.class);
        assertThat(tag).isEqualTo(new SearchOptions.Tag(Long.MAX_VALUE, "停用", false));
    }

    private static Configuration configuration() throws Exception {
        var configuration = MapperRowStub.configuration("/mapper/SearchMapper.xml");
        try (var input = SearchOptionMappingTest.class.getResourceAsStream("/mapper/SearchOptionMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/SearchOptionMapper.xml", configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static Map<String, Object> parameters() {
        return Map.of("spaceId", 1L, "spaceIds", List.of(1L), "principalKeys", List.of("1:7"), "clearance", (short) 1, "limit", 21);
    }
}
