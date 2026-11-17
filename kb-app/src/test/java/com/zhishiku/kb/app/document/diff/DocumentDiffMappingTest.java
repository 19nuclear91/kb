package com.zhishiku.kb.app.document.diff;

import com.zhishiku.kb.app.document.diff.mapper.DocumentDiffMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentDiffMappingTest {
    @Test
    void bindsExactVersionIdentitiesWithThreeSecondTimeouts() throws Exception {
        var configuration = new Configuration();
        String resource = "/mapper/diff/DocumentDiffMapper.xml";
        try (var input = getClass().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var headers = configuration.getMappedStatement(DocumentDiffMapper.class.getName() + ".headers");
        var headerSql = headers.getBoundSql(Map.of("documentId", 1L, "baseVersionId", 9007199254740993L,
                "targetVersionId", Long.MAX_VALUE));
        assertThat(headers.getTimeout()).isEqualTo(3);
        assertThat(headerSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("documentId", "baseVersionId", "targetVersionId");
        var blocks = configuration.getMappedStatement(DocumentDiffMapper.class.getName() + ".blocks");
        assertThat(blocks.getTimeout()).isEqualTo(3);
        var blockSql = blocks.getBoundSql(Map.of("versionId", Long.MAX_VALUE));
        assertThat(blockSql.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("versionId");
        assertThat(configuration.newMetaObject(blockSql.getParameterObject()).getValue("versionId"))
                .isEqualTo(Long.MAX_VALUE);
    }
}
