package com.zhishiku.kb.app.space;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在内存数据库执行生产 XML 中的访问谓词，覆盖例外授权和已过期的空间范围。
 */
class ArchivedDocumentAccessTest {
    private Configuration configuration() throws Exception {
        var config = new Configuration();
        for (String name : List.of("DocumentMapper", "SearchMapper")) {
            try (var input = getClass().getResourceAsStream("/mapper/" + name + ".xml")) {
                new XMLMapperBuilder(input, config, name, config.getSqlFragments()).parse();
            }
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="access">
                  <select id="document" resultType="long">
                    SELECT d.id FROM kb_document d WHERE
                    <include refid="com.zhishiku.kb.app.document.mapper.DocumentMapper.documentAccessPredicate"/>
                  </select>
                  <select id="search" resultType="long">
                    SELECT d.id FROM kb_document d JOIN kb_doc_chunk c ON c.doc_id = d.id WHERE
                    <include refid="com.zhishiku.kb.app.search.mapper.SearchMapper.aclPredicate"/>
                  </select>
                </mapper>
                """;
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)),
                config, "access", config.getSqlFragments()).parse();
        return config;
    }

    @Test
    void archiveBlocksExceptionsAndStaleSpaceScopesAndUnarchiveRestoresThem() throws Exception {
        var config = configuration();
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            try (var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE kb_space(id BIGINT, archived BOOLEAN)");
                sql.execute("CREATE TABLE kb_document(id BIGINT, space_id BIGINT)");
                sql.execute("CREATE TABLE kb_doc_chunk(doc_id BIGINT, space_id BIGINT)");
                sql.execute("CREATE TABLE kb_document_acl(doc_id BIGINT, principal_key VARCHAR, permission VARCHAR)");
                sql.execute("INSERT INTO kb_space VALUES(1, FALSE)");
                sql.execute("INSERT INTO kb_document VALUES(10, 1)");
                sql.execute("INSERT INTO kb_doc_chunk VALUES(10, 1)");
                for (String permission : List.of("view", "download", "edit"))
                    sql.execute("INSERT INTO kb_document_acl VALUES(10, '1:7', '" + permission + "')");
                for (String permission : List.of("view", "download", "edit")) {
                    for (var spaces : List.of(List.<Long>of(), List.of(1L))) {
                        var args = Map.<String, Object>of("spaceIds", spaces,
                                "principalKeys", List.of("1:7"), "aclPermission", permission);
                        sql.execute("UPDATE kb_space SET archived = FALSE");
                        assertThat(query(connection, config, "document", args)).containsExactly(10L);
                        assertThat(query(connection, config, "search", args)).containsExactly(10L);
                        sql.execute("UPDATE kb_space SET archived = TRUE");
                        assertThat(query(connection, config, "document", args)).isEmpty();
                        assertThat(query(connection, config, "search", args)).isEmpty();
                        sql.execute("UPDATE kb_space SET archived = FALSE");
                        assertThat(query(connection, config, "document", args)).containsExactly(10L);
                        assertThat(query(connection, config, "search", args)).containsExactly(10L);
                    }
                }
                assertThat(query(connection, config, "document",
                        Map.of("spaceIds", List.of(), "principalKeys", List.of(), "aclPermission", "view"))).isEmpty();
            }
        }
    }

    private List<Long> query(Connection connection, Configuration config, String name, Map<String, Object> args)
            throws Exception {
        var statement = config.getMappedStatement("access." + name);
        var bound = statement.getBoundSql(args);
        try (var prepared = connection.prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(statement, args, bound).setParameters(prepared);
            var result = new ArrayList<Long>();
            try (var rows = prepared.executeQuery()) {
                while (rows.next()) result.add(rows.getLong(1));
            }
            return result;
        }
    }
}

