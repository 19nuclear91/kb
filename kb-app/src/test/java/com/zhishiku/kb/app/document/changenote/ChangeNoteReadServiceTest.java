package com.zhishiku.kb.app.document.changenote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteHistory;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteRow;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteTarget;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteAccess;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteReadService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChangeNoteReadServiceTest {
    private final ChangeNoteMapper mapper = mock(ChangeNoteMapper.class);
    private final ChangeNoteAccess access = mock(ChangeNoteAccess.class);
    private final ChangeNoteReadService service = new ChangeNoteReadService(mapper, access);

    @Test
    void absentNoteAndFirstVersionHaveZeroVersionAndNullWriteBase() {
        when(mapper.target(10, 1)).thenReturn(new ChangeNoteTarget(10, 1, 2, "published", false, "VERSIONED_REVIEW", "PUBLISHED", false));
        when(mapper.get(10, 1)).thenReturn(new ChangeNoteRow(10, 1, null, null, null, 0, false, false, null));
        var detail = service.get(7, 10, 1);
        assertThat(detail.hasNote()).isFalse();
        assertThat(detail.noteVersion()).isZero();
        assertThat(detail.writeBaseVersionId()).isNull();
    }

    @Test
    void unreadableCandidatesAndMismatchedOwnershipAre404AndDependenciesAre503() {
        doThrow(new ChangeNoteException(ChangeNoteException.Reason.NOT_FOUND)).when(access).version(any(), eq(10L), eq(3L));
        assertThatThrownBy(() -> service.get(7, 10, 3)).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> service.get(7, 10, 99)).hasMessage("NOT_FOUND");
        doThrow(new IllegalStateException("private-dependency")).when(access).current(7);
        assertThatThrownBy(() -> service.get(7, 10, 3)).hasMessage("UNAVAILABLE");
    }

    @Test
    void boundedCursorBindsActorDocumentVersionAndHistoryHasNoActor() throws Exception {
        var now = OffsetDateTime.parse("2026-11-17T00:00:00Z");
        when(mapper.history(10, 3, null, null, 2)).thenReturn(List.of(
                new ChangeNoteHistory(2, 10, 3, now, null, "说明", null, 1L, true, null),
                new ChangeNoteHistory(1, 10, 3, now.minusMinutes(1), null, "旧说明", null, 1L, false, null)));
        var page = service.history(7, 10, 3, 1, null);
        assertThat(page.items()).hasSize(1);
        assertThat(page.nextCursor()).isNotNull();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(page)).doesNotContain("actor", "userId");
        when(mapper.history(10, 3, now, 2L, 2)).thenReturn(List.of());
        assertThat(service.history(7, 10, 3, 1, page.nextCursor()).items()).isEmpty();
        assertThatThrownBy(() -> service.history(7, 10, 4, 1, page.nextCursor())).hasMessage("INVALID_INPUT");
        assertThatThrownBy(() -> service.history(7, 10, 3, 1, "damaged")).hasMessage("INVALID_INPUT");
        for (int limit : List.of(0, 101)) {
            assertThatThrownBy(() -> service.history(7, 10, 3, limit, null)).hasMessage("INVALID_INPUT");
        }
        when(mapper.history(10, 3, null, null, 21)).thenThrow(new IllegalStateException("private-dependency"));
        assertThatThrownBy(() -> service.history(7, 10, 3, 20, null)).hasMessage("UNAVAILABLE");
    }
}
