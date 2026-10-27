package com.zhishiku.kb.app.comment;

import com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper;
import com.zhishiku.kb.app.comment.model.CommentCommand;
import com.zhishiku.kb.app.comment.model.CommentCommandRow;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.OwnComment;
import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.support.BoundValues;
import com.zhishiku.kb.app.support.MapperRowStub;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实评论 MyBatis 映射与替代 JDBC 结果集；不连接数据库，不断言 SQL 文本。 */
class DocumentCommentMappingTest {
    private static final String NS = DocumentCommentMapper.class.getName();
    private static final long DOCUMENT = 9007199254740993L, VERSION = 9007199254740995L;
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant AT = Instant.parse("2026-10-26T00:00:00.123456Z");
    private static Configuration configuration;

    @BeforeAll
    static void load() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        try (var input = DocumentCommentMappingTest.class.getResourceAsStream("/mapper/DocumentCommentMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "/mapper/DocumentCommentMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void rootReplyAndTombstoneRowsMapExactIdsAndStructure() throws Exception {
        var root = MapperRowStub.readOne(configuration, NS + ".find", path(ROOT),
                row(9007199254740997L, ROOT, null, null, 9007199254740999L, "第一行\n第二行", null), CommentRow.class);
        assertThat(root).isEqualTo(new CommentRow(9007199254740997L, ROOT, DOCUMENT, VERSION, null, null,
                9007199254740999L, "第一行\n第二行", AT, null));
        var reply = MapperRowStub.readOne(configuration, NS + ".lockRoot", path(REPLY),
                row(9007199254741001L, REPLY, 9007199254740997L, ROOT, 8L, "回复", null), CommentRow.class);
        assertThat(reply.rootId()).isEqualTo(9007199254740997L);
        assertThat(reply.rootCommentId()).isEqualTo(ROOT);
        assertThat(reply.root()).isFalse();
        var tombstone = MapperRowStub.readOne(configuration, NS + ".find", path(ROOT),
                row(9007199254740997L, ROOT, null, null, 7L, null, AT.plusSeconds(1)), CommentRow.class);
        assertThat(tombstone.deleted()).isTrue();
        assertThat(tombstone.body()).isNull();
    }

    @Test
    void ownLockProjectionAndCommandBindingMapWithoutBody() throws Exception {
        var ownRow = new LinkedHashMap<String, Object>();
        ownRow.put("id", 9007199254741001L);
        ownRow.put("comment_id", REPLY);
        ownRow.put("doc_id", DOCUMENT);
        ownRow.put("version_id", VERSION);
        ownRow.put("root_comment_id", ROOT);
        ownRow.put("deleted", true);
        var parameters = path(REPLY);
        parameters.put("authorUserId", 8L);
        var own = MapperRowStub.readOne(configuration, NS + ".lockOwn", parameters, ownRow, OwnComment.class);
        assertThat(own).isEqualTo(new OwnComment(9007199254741001L, REPLY, DOCUMENT, VERSION, ROOT, true));
        var commandRow = new LinkedHashMap<String, Object>();
        commandRow.put("operation_domain", "COMMENT_CREATE");
        commandRow.put("request_id", "请求-1");
        commandRow.put("actor_user_id", 7L);
        commandRow.put("doc_id", DOCUMENT);
        commandRow.put("version_id", VERSION);
        commandRow.put("parent_comment_id", null);
        commandRow.put("body_digest", "a".repeat(64));
        commandRow.put("comment_id", null);
        var saved = MapperRowStub.readOne(configuration, NS + ".command", new HashMap<>(Map.of("requestId", "请求-1")),
                commandRow, CommentCommandRow.class);
        assertThat(saved).isEqualTo(new CommentCommandRow("COMMENT_CREATE", "请求-1", 7, DOCUMENT, VERSION, null,
                "a".repeat(64), null));
    }

    @Test
    void everyReadAndLockBindsTheFullDocumentVersionPath() {
        for (String id : List.of(NS + ".find", NS + ".lockRoot")) {
            assertThat(BoundValues.values(configuration, id, path(ROOT))).as(id).containsExactly(DOCUMENT, VERSION, ROOT);
        }
        var own = path(ROOT);
        own.put("authorUserId", 7L);
        assertThat(BoundValues.values(configuration, NS + ".lockOwn", own)).containsExactly(DOCUMENT, VERSION, ROOT, 7L);
    }

    @Test
    void rootAndReplyPagesBindOptionalPositionAndLookahead() {
        var first = page(null);
        assertThat(BoundValues.values(configuration, NS + ".roots", first)).containsExactly(DOCUMENT, VERSION, 21);
        var next = page(40L);
        assertThat(BoundValues.values(configuration, NS + ".roots", next)).containsExactly(DOCUMENT, VERSION, 40L, 21);
        var replies = new HashMap<String, Object>(Map.of("documentId", DOCUMENT, "versionId", VERSION, "rootId", 5L,
                "limit", 101));
        replies.put("afterId", null);
        assertThat(BoundValues.values(configuration, NS + ".replies", replies)).containsExactly(DOCUMENT, VERSION, 5L, 101);
        replies.put("afterId", 9L);
        assertThat(BoundValues.values(configuration, NS + ".replies", replies))
                .containsExactly(DOCUMENT, VERSION, 5L, 9L, 101);
    }

    @Test
    void commandBindingStoresDigestButNeverTheBody() {
        var command = CommentCommand.create("请求-1", 7, DOCUMENT, VERSION, ROOT, "敏感正文");
        assertThat(BoundValues.values(configuration, NS + ".claim", command))
                .containsExactly("请求-1", 7L, DOCUMENT, VERSION, ROOT, command.bodyDigest())
                .doesNotContain("敏感正文");
        var complete = new HashMap<String, Object>(Map.of("command", command, "commentId", REPLY));
        assertThat(BoundValues.values(configuration, NS + ".complete", complete))
                .containsExactly(REPLY, "请求-1", 7L, command.bodyDigest());
        assertThat(BoundValues.values(configuration, NS + ".command", "请求-1")).containsExactly("请求-1");
    }

    @Test
    void insertAndEraseBindServerFacts() {
        var insert = new HashMap<String, Object>();
        insert.put("commentId", REPLY);
        insert.put("documentId", DOCUMENT);
        insert.put("versionId", VERSION);
        insert.put("rootId", 5L);
        insert.put("authorUserId", 8L);
        insert.put("body", "回复");
        insert.put("createdAt", AT);
        assertThat(BoundValues.values(configuration, NS + ".insert", insert))
                .containsExactly(REPLY, DOCUMENT, VERSION, 5L, 8L, "回复", AT);
        insert.put("rootId", null);
        assertThat(BoundValues.values(configuration, NS + ".insert", insert))
                .containsExactly(REPLY, DOCUMENT, VERSION, null, 8L, "回复", AT);
        assertThat(BoundValues.values(configuration, NS + ".erase", new HashMap<>(Map.of("id", 5L, "deletedAt", AT))))
                .containsExactly(AT, 5L);
    }

    @Test
    void statementsAreBoundedUncachedAndNeverProjectDocumentOrStorageColumns() {
        List<String> forbidden = List.of("title", "file_name", "storage_key", "content_hash", "label", "display_name",
                "username");
        for (String name : List.of("find", "lockRoot", "roots", "replies", "lockOwn", "command", "insert", "claim",
                "complete", "erase")) {
            var statement = configuration.getMappedStatement(NS + "." + name);
            assertThat(statement.getTimeout()).as(name).isEqualTo(5);
            if (statement.getSqlCommandType() == SqlCommandType.SELECT) {
                assertThat(statement.isUseCache()).as(name).isFalse();
            }
            for (var map : statement.getResultMaps()) {
                assertThat(map.getMappedColumns().stream().map(String::toLowerCase)).as(name).noneMatch(forbidden::contains);
            }
        }
        for (String name : List.of("lockOwn", "command")) {
            for (var map : configuration.getMappedStatement(NS + "." + name).getResultMaps()) {
                assertThat(map.getMappedColumns().stream().map(String::toLowerCase)).as(name).doesNotContain("body");
            }
        }
    }

    private static Map<String, Object> path(UUID comment) {
        return new HashMap<>(Map.of("documentId", DOCUMENT, "versionId", VERSION, "commentId", comment));
    }

    private static Map<String, Object> page(Long before) {
        var parameters = new HashMap<String, Object>(Map.of("documentId", DOCUMENT, "versionId", VERSION, "limit", 21));
        parameters.put("beforeId", before);
        return parameters;
    }

    private static Map<String, Object> row(long id, UUID comment, Long rootId, UUID rootComment, long author, String body,
                                           Instant deletedAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("comment_id", comment);
        row.put("doc_id", DOCUMENT);
        row.put("version_id", VERSION);
        row.put("root_id", rootId);
        row.put("root_comment_id", rootComment);
        row.put("author_user_id", author);
        row.put("body", body);
        row.put("created_at", AT);
        row.put("deleted_at", deletedAt);
        return row;
    }
}
