package com.zhishiku.kb.app.query;

import com.zhishiku.kb.app.space.mapper.UploadSpaceMapper;
import com.zhishiku.kb.app.space.model.UploadSpaceCandidate;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅内存映射和 Mock JDBC 结果集，不获取连接或执行 SQL。
 */
class UserResourceMappingTest {
    @Test void bindsPositionAndRangeAndConstructsOnlyMinimalProjection() throws Exception {
        var config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UploadSpaceMapper.class);
        var statement = UploadSpaceMapper.class.getName() + ".list";
        var params = new LinkedHashMap<String, Object>();
        params.put("spaces", List.of(3L));
        params.put("afterId", 1L);
        params.put("fetch", 21);
        var row = MapperRowStub.readOne(config, statement, params, Map.of("space_id", 3L, "name", "空间"), UploadSpaceCandidate.class);
        assertThat(row).isEqualTo(new UploadSpaceCandidate(3, "空间"));
        assertThat(config.getMappedStatement(statement).getBoundSql(params).getParameterMappings()).hasSize(3);
        assertThat(config.getMappedStatement(statement).getBoundSql(params).getSql()).contains("archived = false");
        params.put("spaces", List.of());
        assertThat(config.getMappedStatement(statement).getBoundSql(params).getParameterMappings()).hasSize(2);
        assertThat(config.getMappedStatement(statement).getBoundSql(params).getSql()).contains("FALSE");
    }
}
