package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteReadService;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeRows;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReadTest {
    private final SubscriptionAccess access = mock(SubscriptionAccess.class);
    private final KnowledgeQueryMapper mapper = mock(KnowledgeQueryMapper.class);
    private final ChangeNoteReadService notes = mock(ChangeNoteReadService.class);
    private final KnowledgeUpdateReadServiceImpl service = new KnowledgeUpdateReadServiceImpl(access, mapper, notes);
    private final UUID id = UUID.randomUUID();

    @Test
    void fixedVersionReadsLatestManualNoteAndRetainsOriginalV1BaseForV3() {
        when(access.current(7)).thenReturn(PermissionScope.of(7, (short) 1, List.of(3L), List.of("1:7")));
        var row = new KnowledgeRows.Update(id, 10, 11, 23, 3, "当前标题", Instant.EPOCH, LocalDate.of(2026, 11, 23), SubscriptionMode.WEEKLY);
        when(mapper.update(any())).thenReturn(row);
        when(notes.get(7, 11, 23)).thenReturn(new ChangeNoteViews.Detail(11, 23, true, "最新人工说明", 21L, 1,
                true, true, 2, 21L, 1, false, false, true));
        when(mapper.base(any(), eq(23L), eq(21L))).thenReturn(new KnowledgeRows.Base(21, 1));
        var result = service.detail(7, id);
        assertThat(result.update().versionId()).isEqualTo("23");
        assertThat(result.baseVersionId()).isEqualTo("21");
        assertThat(result.text()).isEqualTo("最新人工说明");
        assertThat(result.corrected()).isTrue();
        when(mapper.base(any(), eq(23L), eq(21L))).thenReturn(null);
        assertThat(service.detail(7, id).comparisonStatus()).isEqualTo("BASE_UNAVAILABLE");
        assertThat(service.detail(7, id).baseVersionId()).isNull();
        when(mapper.update(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(7, id)).isInstanceOf(SubscriptionFailure.class).hasMessage("NOT_FOUND");
    }

    @Test
    void digestGroupsUseVisibleCountsAndBindEachPageToItsOriginalDigest() {
        when(access.current(7)).thenReturn(PermissionScope.of(7, (short) 1, List.of(3L), List.of("1:7")));
        var week = LocalDate.of(2026, 11, 23);
        when(mapper.digest(any())).thenReturn(new KnowledgeRows.Digest(id, week, Instant.EPOCH));
        when(mapper.groups(any())).thenAnswer(call -> {
            com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery query = call.getArgument(0);
            assertThat(query.actor()).isEqualTo(7);
            assertThat(query.digestId()).isEqualTo(id);
            return List.of(new KnowledgeRows.Group(11, "可见文档", 1, 10, Instant.EPOCH),
                    new KnowledgeRows.Group(12, "下一文档", 2, 9, Instant.EPOCH));
        });
        var result = service.digest(7, id, 1, null);
        assertThat(result.groups().items()).hasSize(1);
        assertThat(result.groups().items().getFirst().publicationCount()).isEqualTo("1");
        assertThat(result.groups().hasMore()).isTrue();
        assertThatThrownBy(() -> service.digest(7, UUID.randomUUID(), 1, result.groups().nextCursor()))
                .isInstanceOf(SubscriptionFailure.class).hasMessage("INVALID_INPUT");
        when(mapper.digest(any())).thenReturn(null);
        assertThatThrownBy(() -> service.digest(7, id, 20, null)).isInstanceOf(SubscriptionFailure.class).hasMessage("NOT_FOUND");
    }

    @Test
    void noteWithOriginalNullBaseDoesNotInventLaterBaseAndFailuresRemainFailures() {
        when(access.current(7)).thenReturn(PermissionScope.of(7, (short) 1, List.of(), List.of("1:7")));
        when(mapper.update(any())).thenReturn(new KnowledgeRows.Update(id, 10, 11, 23, 3, "标题", Instant.EPOCH,
                LocalDate.of(2026, 11, 23), SubscriptionMode.PER_PUBLICATION));
        when(notes.get(7, 11, 23)).thenReturn(new ChangeNoteViews.Detail(11, 23, true, "首次说明", null, null,
                false, false, 1, null, null, false, false, true));
        assertThat(service.detail(7, id).comparisonStatus()).isEqualTo("NO_BASE");
        verify(mapper, never()).base(any(), any(Long.class), any());
        when(notes.get(7, 11, 23)).thenThrow(new IllegalStateException("notes unavailable"));
        assertThatThrownBy(() -> service.detail(7, id)).isInstanceOf(IllegalStateException.class);
    }
}
