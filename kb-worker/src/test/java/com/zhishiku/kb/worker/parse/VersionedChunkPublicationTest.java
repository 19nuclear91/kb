package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import com.zhishiku.kb.worker.parse.mapper.ReviewFactMapper;
import com.zhishiku.kb.worker.parse.mapper.VersionPublicationMapper;
import com.zhishiku.kb.worker.parse.model.IndexPublicationCommit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VersionedChunkPublicationTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final VersionPublicationMapper publication = mock(VersionPublicationMapper.class);
    private final ReviewFactMapper facts = mock(ReviewFactMapper.class);
    private final ChunkPublisher publisher = new ChunkPublisher(jdbc, state, publication, facts);
    private final ParsePipeline.ParsedDocument parsed = new ParsePipeline.ParsedDocument(List.of(
            new ParsePipeline.IndexedChunk(0, "text", 1, "heading", ParseEmbeddingFixture.vector())),
            "fixture", "1", false, List.of(), ParseEmbeddingFixture.execution(ParseEmbeddingFixture.task()));

    @BeforeEach
    void setup() {
        when(state.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<Integer>>any(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class))).thenAnswer(call -> {
            var constructor = Class.forName("com.zhishiku.kb.worker.parse.ChunkPublisher$VersionInfo").getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return constructor.newInstance(42L, 2, "published");
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.batchUpdate(anyString(), ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(), any()))
                .thenReturn(new int[][]{{1}});
        when(publication.lockState(2)).thenReturn(ParseEmbeddingFixture.publication());
        when(publication.lockDocumentForVersion(2)).thenReturn(42L);
        when(publication.indexed(any())).thenReturn(1);
        when(publication.publishAuto(eq(2L), any(), any(), any())).thenReturn(1);
        when(facts.append(any())).thenReturn(1);
    }

    @Test
    void reviewRequiredV2OnlyIndexesAndKeepsV1ServingWithoutPublicationFact() {
        var target = ParseEmbeddingFixture.publication();
        target.setPublicationPolicy(PublicationPolicy.REVIEW_REQUIRED);
        target.setVersionNo(2);
        target.setCurrentVersionNo(1);
        when(publication.lockState(2)).thenReturn(target);
        assertThat(publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isTrue();
        verify(publication).indexed(any());
        verify(publication, never()).publishAuto(anyLong(), any(), any(), any());
        verifyNoInteractions(facts);
        assertThat(target.getCurrentVersionNo()).isEqualTo(1);
        assertThat(target.getPublicationEvidence()).isEqualTo(PublicationEvidence.NONE);
    }

    @Test
    void firstAutoPublicationCarriesGeneratedIndexIdentityAndRealPublicationFact() {
        assertThat(publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isTrue();
        var commit = ArgumentCaptor.forClass(IndexPublicationCommit.class);
        verify(publication).indexed(commit.capture());
        assertThat(commit.getValue().profileId()).isEqualTo(ParseEmbeddingFixture.PROFILE.profileId());
        assertThat(commit.getValue().fingerprint()).isEqualTo(ParseEmbeddingFixture.PROFILE.fingerprint());
        assertThat(commit.getValue().chunkCount()).isEqualTo(1);
        var event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(facts).append(event.capture());
        assertThat(event.getValue().kind()).isEqualTo(CollaborationKind.VERSION_PUBLISHED);
        assertThat(event.getValue().versionId()).isEqualTo(2);
        assertThat(event.getValue().occurredAt()).isNotNull();
    }

    @Test
    void higherCurrentVersionAndArchivedSpaceNeverAutoPublishOldCandidate() {
        var target = ParseEmbeddingFixture.publication();
        target.setCurrentVersionNo(3);
        when(publication.lockState(2)).thenReturn(target);
        assertThat(publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isTrue();
        target.setCurrentVersionNo(null);
        target.setSpaceArchived(true);
        assertThat(publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isTrue();
        verify(publication, never()).publishAuto(anyLong(), any(), any(), any());
        verifyNoInteractions(facts);
    }

    @Test
    void alreadyPublishedCannotBeRewrittenAndPartialBatchCannotBecomeIndexed() {
        var target = ParseEmbeddingFixture.publication();
        target.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        target.setPublishedAt(Instant.EPOCH);
        when(publication.lockState(2)).thenReturn(target);
        assertThat(publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isFalse();
        verify(publication, never()).indexed(any());
        target.setPublicationEvidence(PublicationEvidence.NONE);
        target.setPublishedAt(null);
        when(jdbc.batchUpdate(anyString(), ArgumentMatchers.<List<ParsePipeline.IndexedChunk>>any(), anyInt(), any()))
                .thenReturn(new int[][]{{0}});
        assertThatThrownBy(() -> publisher.publish(ParseEmbeddingFixture.task(), "owner", parsed)).isInstanceOf(IllegalStateException.class);
        verify(publication, never()).indexed(any());
    }
}
