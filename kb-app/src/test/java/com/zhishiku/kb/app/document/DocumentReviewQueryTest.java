package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewListRow;
import com.zhishiku.kb.app.document.service.DocumentReviewQueryService;
import com.zhishiku.kb.app.document.service.PublicationReadinessService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentReviewQueryTest {
    private final ReviewCommandFixture f = new ReviewCommandFixture();
    private final DocumentReviewQueryMapper queries = mock(DocumentReviewQueryMapper.class);
    private final EmbeddingControlService embedding = mock(EmbeddingControlService.class);
    private final DocumentReviewQueryService service = new DocumentReviewQueryService(queries, f.mapper, f.auth, f.eligibility,
            embedding, mock(PublicationReadinessService.class));

    private void activeControl() {
        when(embedding.current()).thenReturn(new EmbeddingControl("ACTIVE", "p", "fp", null, null, 3, null));
    }

    @Test
    void editOnlyUploaderGetsMinimalStatusAndCannotLoadOpinionsOrFiles() throws Exception {
        activeControl();
        when(f.auth.can(7, 10, "edit")).thenReturn(true);
        var result = service.status(7, 10, 20, Map.of());
        assertThat(result.capabilities().canSubmit()).isTrue();
        assertThat(result.capabilities().canRead()).isFalse();
        assertThat(result.capabilities().canDownload()).isFalse();
        assertThat(result.capabilities().canReadOpinions()).isFalse();
        assertThat(result.state().currentVersionId()).isNull();
        assertThat(result.rounds()).isEmpty();
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertThat(json).doesNotContain("title", "fileName", "objectKey", "reason", "uploader", "submittedBy", "fingerprint");
        assertThatThrownBy(() -> service.status(7, 10, 20, Map.of("opinions", List.of("true"))))
                .isInstanceOf(ReviewFailure.class).hasMessage("NOT_FOUND");
        verify(f.mapper, never()).privateRounds(anyLong(), anyLong(), anyInt());
    }

    @Test
    void pendingRepositoryFiltersBeforePageAndOldCursorDoesNotRetainRevokedEligibility() {
        var allowed = new AtomicReference<>(Set.of(30L, 28L, 26L));
        when(queries.pending(eq(9L), any(), any(), anyInt())).thenAnswer(call -> {
            Long before = call.getArgument(1);
            int limit = call.getArgument(3);
            var filtered = new ArrayList<ReviewListRow>();
            for (long id = 30; id >= 20; id--) {
                if (allowed.get().contains(id) && (before == null || id < before)) filtered.add(row(id));
            }
            return filtered.stream().limit(limit).toList();
        });
        var first = service.list(9, Map.of("kind", List.of("pending"), "limit", List.of("2")));
        assertThat(first.items()).extracting(item -> item.state().versionId()).containsExactly(30L, 28L);
        assertThat(first.hasMore()).isTrue();
        allowed.set(Set.of());
        assertThat(service.list(9, Map.of("kind", List.of("pending"), "limit", List.of("2"),
                "cursor", List.of(first.nextCursor()))).items()).isEmpty();
        assertThatThrownBy(() -> service.list(8, Map.of("kind", List.of("pending"), "cursor", List.of(first.nextCursor()))))
                .isInstanceOf(QueryException.class);
    }

    @Test
    void mineNeverProjectsTitleEvenIfRepositorySuppliesItAndDefaultLimitIsBounded() {
        when(queries.mine(7, null, 21)).thenReturn(List.of(row(30)));
        var page = service.list(7, Map.of("kind", List.of("mine")));
        assertThat(page.items().getFirst().title()).isNull();
        assertThat(page.items().getFirst().state().currentVersionId()).isNull();
        assertThat(page.hasMore()).isFalse();
        verify(queries).mine(7, null, 21);
        for (var params : List.of(Map.of("kind", List.of("all")), Map.of("kind", List.of("mine", "pending")),
                Map.of("kind", List.of("mine"), "limit", List.of("101")), Map.of("actor", List.of("8")))) {
            assertThatThrownBy(() -> service.list(7, params)).isInstanceOf(QueryException.class);
        }
    }

    @Test
    void explicitPrivateOpinionsAreBoundedAndOrdinaryPublishedViewDoesNotGrantThem() {
        activeControl();
        f.version.setPublicationEvidence(PublicationEvidence.PUBLISHED);
        when(f.auth.can(9, 10, "view")).thenReturn(true);
        assertThat(service.status(9, 10, 20, Map.of()).capabilities().canRead()).isTrue();
        assertThatThrownBy(() -> service.status(9, 10, 20, Map.of("opinions", List.of("true"))))
                .isInstanceOf(ReviewFailure.class);
        when(f.auth.can(7, 10, "view")).thenReturn(true);
        when(f.auth.participant(7, f.version, null)).thenReturn(true);
        var old = DocumentReviewRow.builder().id(UUID.randomUUID()).docId(10).versionId(20).roundNo(1)
                .submittedBy(7).state(ReviewState.REJECTED).submittedAt(Instant.EPOCH).reason("私有原因").build();
        when(f.mapper.privateRounds(20, Long.MAX_VALUE, 21)).thenReturn(List.of(old));
        assertThat(service.status(7, 10, 20, Map.of("opinions", List.of("true"))).rounds()).singleElement()
                .satisfies(round -> assertThat(round.reason()).isEqualTo("私有原因"));
    }

    private static ReviewListRow row(long id) {
        var row = new ReviewListRow();
        row.setDocumentId(10);
        row.setVersionId(id);
        row.setVersionNo(2);
        row.setIndexStatus("indexed");
        row.setPublicationPolicy(PublicationPolicy.REVIEW_REQUIRED);
        row.setPublicationEvidence(PublicationEvidence.NONE);
        row.setSubmittedAt(Instant.EPOCH.plusSeconds(id));
        row.setReviewState(ReviewState.IN_REVIEW);
        row.setCanRead(true);
        row.setTitle("仅待办可见标题");
        return row;
    }
}
