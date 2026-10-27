package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.model.ParseExecution;
import com.zhishiku.kb.worker.parse.mapper.VersionPublicationMapper;
import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ParsePublishIdentityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final VersionPublicationMapper publication = mock(VersionPublicationMapper.class);
    private final ChunkPublisher publisher = new ChunkPublisher(jdbc, state, publication, mock(ReviewFactMapper.class));

    @Test
    void refusesBareResultsWrongTokenOrWrongOwnerWithoutWrites() {
        var task = ParseEmbeddingFixture.task();
        assertThat(publisher.publish(task, "owner", parsed(null))).isFalse();
        assertThat(publisher.publish(task, "other", parsed(ParseEmbeddingFixture.execution(task)))).isFalse();
        assertThat(publisher.publish(task, "owner", parsed(new ParseExecution(task.id(), task.versionId(),
                "owner", 4L, 8L, ParseEmbeddingFixture.PROFILE)))).isFalse();
        verifyNoInteractions(jdbc, state);
    }

    @Test
    void rejectsMaintenanceChangedEpochAndWrongProfileBeforeTaskLock() {
        var task = ParseEmbeddingFixture.task();
        for (var control : List.of(
                new EmbeddingControl("QUIESCING", null, null, "target", "fp", 5, UUID.randomUUID()),
                new EmbeddingControl("ACTIVE", task.embeddingProfileId(), task.embeddingFingerprint(), null, null, 5,
                        null),
                new EmbeddingControl("ACTIVE", "other", "other", null, null, 4, null))) {
            when(state.lockShared()).thenReturn(control);
            assertThat(publisher.publish(task, "owner", parsed(ParseEmbeddingFixture.execution(task)))).isFalse();
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void expiredOrReclaimedTaskDoesNotTouchVersionsOrChunks() {
        when(state.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        var task = ParseEmbeddingFixture.task();
        assertThat(publisher.publish(task, "owner", parsed(ParseEmbeddingFixture.execution(task)))).isFalse();
        verify(jdbc).query(contains("claim_token = ?"), ArgumentMatchers.<ResultSetExtractor<Integer>>any(),
                eq(task.id()), eq("owner"), eq(task.versionId()), eq(9L), eq(4L), eq(task.embeddingProfileId()));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void deletedDocumentCannotReappear() {
        allowTaskAndVersion("deleted");
        var task = ParseEmbeddingFixture.task();
        assertThat(publisher.publish(task, "owner", parsed(ParseEmbeddingFixture.execution(task)))).isFalse();
        verify(jdbc, never()).update(contains("DELETE FROM kb_doc_chunk"), any(Object[].class));
        verify(jdbc, never()).update(contains("current_version_id"), any(Object[].class));
    }

    @Test
    void publishesGeneratedProfileAndReadsCurrentPermissionFacts() throws Exception {
        allowTaskAndVersion("published");
        when(jdbc.update(contains("kb_parse_task"), any(Object[].class))).thenReturn(1);
        PreparedStatement statement = mock(PreparedStatement.class);
        doAnswer(invocation -> {
            List<ParsePipeline.IndexedChunk> chunks = invocation.getArgument(1);
            ParameterizedPreparedStatementSetter<ParsePipeline.IndexedChunk> setter = invocation.getArgument(3);
            setter.setValues(statement, chunks.getFirst());
            return new int[][]{{1}};
        }).when(jdbc).batchUpdate(contains("INSERT INTO kb_doc_chunk"),
                ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(), any());
        var task = ParseEmbeddingFixture.task();
        assertThat(publisher.publish(task, "owner", parsed(ParseEmbeddingFixture.execution(task)))).isTrue();
        verify(statement).setString(15, ParseEmbeddingFixture.PROFILE.model());
        verify(statement).setString(16, ParseEmbeddingFixture.PROFILE.profileId());
        verify(jdbc).batchUpdate(contains("d.space_id, d.security_level"),
                ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(), any());
    }

    @Test
    void resultVectorIsDefensive() {
        var chunk = parsed(null).chunks().getFirst();
        chunk.embedding()[0] = 0;
        assertThat(chunk.embedding()[0]).isEqualTo(0.1f);
    }

    private ParsePipeline.ParsedDocument parsed(ParseExecution execution) {
        return new ParsePipeline.ParsedDocument(List.of(new ParsePipeline.IndexedChunk(0, "text", 1,
                "heading", ParseEmbeddingFixture.vector())), "fixture", "1", false, List.of(), execution);
    }

    private void allowTaskAndVersion(String status) {
        when(publication.lockDocumentForVersion(2)).thenReturn(42L);
        when(publication.lockState(2)).thenReturn(ParseEmbeddingFixture.publication());
        when(publication.indexed(any())).thenReturn(1);
        when(state.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Integer>>any(), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    var constructor = Class.forName("com.zhishiku.kb.worker.parse.ChunkPublisher$VersionInfo")
                            .getDeclaredConstructors()[0];
                    constructor.setAccessible(true);
                    return constructor.newInstance(42L, 1, status);
                });
    }
}
