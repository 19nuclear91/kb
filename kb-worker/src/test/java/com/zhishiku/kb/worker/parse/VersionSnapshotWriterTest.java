package com.zhishiku.kb.worker.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.worker.parse.model.DocumentBlock;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VersionSnapshotWriterTest {
    @Test
    void emptyBlocksOnlyDeletePreviousSnapshot() {
        var jdbc = mock(JdbcTemplate.class);
        new VersionSnapshotWriter(jdbc, new ObjectMapper()).replace(42, ParseEmbeddingFixture.task(),
                new ParsePipeline.ParsedDocument(List.of()));
        verify(jdbc).update(anyString(), ArgumentMatchers.<Object[]>any());
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void writesClaimGenerationCodePointCountAndFullTableCells() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var claimed = ParseEmbeddingFixture.task();
        var task = new ParseTask(claimed.id(), claimed.versionId(), 1, 3, claimed.leaseUntil(), "owner",
                null, null, null, null, null, 17L, "key", "a.txt", 100L, "text/plain",
                claimed.embeddingProfileId(), claimed.embeddingFingerprint(), claimed.embeddingEpoch(), claimed.claimToken());
        var cells = List.of(List.of("\t\"\\😀", "长".repeat(3000)));
        var parsed = new ParsePipeline.ParsedDocument(List.of(), "parser", "7", true, List.of(), null,
                List.of(new DocumentBlock(DocumentBlock.Kind.TABLE, "😀正文", 1, "标题", null, cells)));
        when(jdbc.update(contains("INSERT INTO"), any(Object[].class))).thenReturn(1);
        var ps = mock(PreparedStatement.class);
        when(jdbc.batchUpdate(anyString(), ArgumentMatchers.<List<Integer>>any(), anyInt(),
                ArgumentMatchers.<ParameterizedPreparedStatementSetter<Integer>>any())).thenAnswer(call -> {
                    ParameterizedPreparedStatementSetter<Integer> setter = call.getArgument(3);
                    setter.setValues(ps, 0);
                    return new int[][]{{1}};
                });

        new VersionSnapshotWriter(jdbc, new ObjectMapper()).replace(42, task, parsed);

        var order = inOrder(jdbc);
        order.verify(jdbc).update(contains("DELETE"), eq(task.versionId()));
        order.verify(jdbc).update(contains("INSERT INTO kb_document_version_snapshot"),
                eq(task.versionId()), eq(42L), eq(task.id()), eq(17L), eq("parser"), eq("7"),
                eq(true), eq(1), eq(3L));
        order.verify(jdbc).batchUpdate(anyString(), ArgumentMatchers.<List<Integer>>any(), anyInt(), any());
        verify(ps).setInt(2, 0);
        verify(ps).setString(12, new ObjectMapper().writeValueAsString(cells));
    }
}
