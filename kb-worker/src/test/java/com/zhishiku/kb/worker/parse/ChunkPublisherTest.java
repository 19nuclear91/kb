package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParseIssue;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import com.zhishiku.kb.worker.parse.model.SourceLocation;
import com.zhishiku.kb.worker.parse.mapper.VersionPublicationMapper;
import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChunkPublisherTest {

    @Test
    void refusesStructuredErrorsBeforeTouchingDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingStateMapper embeddings = mock(EmbeddingStateMapper.class);
        ChunkPublisher publisher = new ChunkPublisher(jdbc, embeddings, mock(VersionPublicationMapper.class), mock(ReviewFactMapper.class));
        ParseTask task = new ParseTask(1L, 2L, 1, 3,
                OffsetDateTime.now().plusMinutes(1), "owner", "key", "a.txt", 1L,
                "text/plain");
        ParsePipeline.ParsedDocument parsed = new ParsePipeline.ParsedDocument(
                List.of(), "fixture", "1", false,
                List.of(new ParseIssue(ParseIssue.Severity.ERROR, "UNSUPPORTED",
                        "不支持", SourceLocation.unknown())));

        assertThatThrownBy(() -> publisher.publish(task, "owner", parsed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("禁止发布");
        verifyNoInteractions(jdbc);
    }

    @Test
    void structuredResultCarriesTableMetadataToIndexedChunkContract() {
        ParsePipeline.IndexedChunk chunk = new ParsePipeline.IndexedChunk(
                0, "| a | b |", 1, "标题",
                new SourceLocation(1, 2, 2, 10, 18, "table-1"),
                DocumentBlock.Kind.TABLE,
                List.of(List.of("a", "b")), new float[]{0.1f});

        assertThat(chunk.kind()).isEqualTo(DocumentBlock.Kind.TABLE);
        assertThat(chunk.tableCells()).containsExactly(List.of("a", "b"));
        assertThat(chunk.source().locator()).isEqualTo("table-1");
    }

    /**
     * 发布必须先拿到文档行锁，才能动分块。
     *
     * <p>这是「发布与文档所属空间变更并发」场景所要求的串行化前提。搬迁事务在写之前
     * 取同一把锁，所以两者只能一前一后；锁只取搬迁一侧不够——发布侧不取锁时，它的
     * {@code INSERT} 可以在搬迁的 {@code UPDATE} 之后提交，那批新分块就带着旧
     * {@code space_id} 落地，并继续按<b>原空间</b>参与检索过滤。
     *
     * <p>真实的并发行为需要 PG 才能验证；这里只守"取锁发生在分块写入之前"这个结构性
     * 事实——它是那条行为的必要条件，也是唯一能在单测里守住的部分。
     */
    @Test
    void takesTheDocumentRowLockBeforeTouchingChunks() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingStateMapper embeddings = mock(EmbeddingStateMapper.class);
        when(embeddings.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Integer>>any(),
                any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<Object>>any(),
                any(Object[].class)))
                .thenAnswer(invocation -> newVersionInfo(42L, 1, "published"));
        when(jdbc.update(contains("kb_parse_task"), any(Object[].class))).thenReturn(1);

        ParseTask task = ParseEmbeddingFixture.task();
        ParsePipeline.IndexedChunk chunk = new ParsePipeline.IndexedChunk(
                0, "正文", 1, "标题", SourceLocation.unknown(),
                DocumentBlock.Kind.PARAGRAPH, List.of(), ParseEmbeddingFixture.vector());

        var publication = mock(VersionPublicationMapper.class);
        when(publication.lockDocumentForVersion(2)).thenReturn(42L);
        when(publication.lockState(2)).thenReturn(ParseEmbeddingFixture.publication());
        when(publication.indexed(any())).thenReturn(1);
        when(jdbc.batchUpdate(contains("INSERT INTO kb_doc_chunk"),
                ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(), any())).thenReturn(new int[][]{{1}});
        ChunkPublisher publisher = new ChunkPublisher(jdbc, embeddings, publication, mock(ReviewFactMapper.class));
        publisher.publish(task, "owner", new ParsePipeline.ParsedDocument(List.of(chunk), "fixture", "1",
                false, List.of(), ParseEmbeddingFixture.execution(task)));

        InOrder ordered = inOrder(embeddings, jdbc);
        ordered.verify(embeddings).lockShared();
        ordered.verify(jdbc).query(contains("FROM kb_parse_task"),
                ArgumentMatchers.<ResultSetExtractor<Integer>>any(), any(Object[].class));
        ordered.verify(jdbc).query(contains("FROM kb_document WHERE id = ? FOR UPDATE"),
                ArgumentMatchers.<ResultSetExtractor<Integer>>any(), any(Object[].class));
        ordered.verify(jdbc).update(contains("DELETE FROM kb_doc_chunk"), any(Object[].class));
        ordered.verify(jdbc).batchUpdate(contains("INSERT INTO kb_doc_chunk"),
                ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(),
                ArgumentMatchers.any());
    }

    /**
     * 构造一个 {@code ChunkPublisher.VersionInfo}。
     *
     * <p>它是包私有 record，测试在同一个包里，所以直接反射拿到唯一的构造器即可——
     * 把它提成 public 只为测试服务，反而会把一个内部投影暴露成 API。
     */
    private static Object newVersionInfo(long docId, int versionNo, String status) throws Exception {
        var versionInfo = Class.forName("com.zhishiku.kb.worker.parse.ChunkPublisher$VersionInfo");
        var constructor = versionInfo.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(docId, versionNo, status);
    }
}
