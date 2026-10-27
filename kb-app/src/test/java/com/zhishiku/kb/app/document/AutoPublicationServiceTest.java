package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.document.mapper.DocumentPublicationMapper;
import com.zhishiku.kb.app.document.model.IndexIntegrity;
import com.zhishiku.kb.app.document.service.AutoPublicationService;
import com.zhishiku.kb.app.document.service.AutoPublicationTransaction;
import com.zhishiku.kb.app.document.service.PublicationReadinessService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoPublicationServiceTest {
    @Test
    void restorationRechecksSpaceVersionProfileAndEvidenceInsteadOfUnconditionallyPublishing() {
        var f = new ReviewCommandFixture();
        var publication = mock(DocumentPublicationMapper.class);
        var embedding = mock(EmbeddingControlService.class);
        when(embedding.lockShared()).thenReturn(new EmbeddingControl("ACTIVE", "p", "fp", null, null, 4, null));
        var integrity = new IndexIntegrity();
        integrity.setChunkCount(1);
        integrity.setValidCount(1);
        integrity.setDistinctIndexes(1);
        integrity.setFirstIndex(0);
        integrity.setLastIndex(0);
        when(publication.integrity(10, 20, "p")).thenReturn(integrity);
        when(publication.publish(eq(10L), eq(20L), eq(0L), eq("AUTO"), eq("p"), eq("fp"), any())).thenReturn(1);
        f.version.setPublicationPolicy(PublicationPolicy.AUTO);
        f.version.setIndexedProfileId("p");
        f.version.setIndexedFingerprint("fp");
        f.version.setIndexedEpoch(2L);
        f.version.setIndexedChunkCount(1);
        f.version.setSpaceArchived(true);
        var transaction = new AutoPublicationTransaction(embedding, f.mapper, publication,
                new PublicationReadinessService(publication), f.events);
        var source = new SourceKey(10, 20);
        assertThat(transaction.publish(source)).isFalse();
        f.version.setSpaceArchived(false);
        f.version.setCurrentVersionNo(3);
        assertThat(transaction.publish(source)).isFalse();
        f.version.setCurrentVersionNo(null);
        f.version.setIndexedFingerprint("old");
        assertThat(transaction.publish(source)).isFalse();
        f.version.setIndexedFingerprint("fp");
        f.version.setPublicationPolicy(PublicationPolicy.REVIEW_REQUIRED);
        assertThat(transaction.publish(source)).isFalse();
        f.version.setPublicationPolicy(PublicationPolicy.AUTO);
        assertThat(transaction.publish(source)).isTrue();
        f.version.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        assertThat(transaction.publish(source)).isFalse();
        verify(f.eventMapper, times(1)).append(any());
    }

    @Test
    void eachBatchIsBoundedAndWrapsForPreviouslyBlockedCandidates() {
        var mapper = mock(DocumentPublicationMapper.class);
        var transaction = mock(AutoPublicationTransaction.class);
        var service = new AutoPublicationService(mapper, transaction);
        when(mapper.autoCandidates(0, 2)).thenReturn(List.of(new SourceKey(10, 20), new SourceKey(11, 21)));
        assertThat(service.scan(0, 2)).isEqualTo(21);
        when(mapper.autoCandidates(21, 2)).thenReturn(List.of());
        assertThat(service.scan(21, 2)).isZero();
        assertThatThrownBy(() -> service.scan(0, 101)).isInstanceOf(IllegalArgumentException.class);
        verify(transaction, times(2)).publish(any());
        verify(mapper, never()).autoCandidates(anyLong(), eq(101));
    }
}
