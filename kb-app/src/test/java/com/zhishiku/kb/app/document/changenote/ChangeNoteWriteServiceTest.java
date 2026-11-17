package com.zhishiku.kb.app.document.changenote;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteCommandMapper;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteCommand;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteHistory;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteRow;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteTarget;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteAccess;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteCommands;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChangeNoteWriteServiceTest {
    private final ChangeNoteMapper mapper = mock(ChangeNoteMapper.class);
    private final ChangeNoteCommandMapper receipts = mock(ChangeNoteCommandMapper.class);
    private final ChangeNoteAccess access = mock(ChangeNoteAccess.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final ChangeNoteCommands commands = new ChangeNoteCommands(receipts, new ObjectMapper());
    private final ChangeNoteWriteService service = new ChangeNoteWriteService(mapper, access, commands, audit);
    private final AtomicReference<ChangeNoteRow> row = new AtomicReference<>(new ChangeNoteRow(10, 3, null, null, null, 0, false, false, null));

    @BeforeEach
    void ready() {
        target(false, false, "published");
        when(mapper.get(10, 3)).thenAnswer(call -> row.get());
        when(mapper.base(10, 3)).thenReturn(new ChangeNoteMapper.Base(1, 1));
        when(mapper.insert(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(mapper.update(any())).thenAnswer(call -> store(call.getArgument(0)));
        when(mapper.appendHistory(any())).thenReturn(1);
        when(receipts.insert(any())).thenReturn(1);
    }

    private int store(ChangeNoteRow next) {
        row.set(new ChangeNoteRow(next.documentId(), next.versionId(), next.text(), next.baseVersionId(), next.baseVersionNo(),
                next.noteVersion(), next.corrected(), next.createdAfterVisible(), OffsetDateTime.parse("2026-11-17T00:00:00Z")));
        return 1;
    }

    private void target(boolean visible, boolean frozen, String status) {
        var target = new ChangeNoteTarget(10, 3, 2, status, false, "VERSIONED_REVIEW", visible ? "PUBLISHED" : "NONE", frozen);
        when(mapper.lockVersion(10, 3)).thenReturn(target);
        when(mapper.target(10, 3)).thenReturn(target);
    }

    private ChangeNoteInput input(String text, String reason) {
        return new ChangeNoteInput(text, reason, row.get().noteVersion());
    }

    @Test
    void draftWritesAndClearsWithoutReasonRecomputingBaseEachTime() {
        service.update(7, 10, 3, input("初稿", null), "first");
        assertThat(row.get().baseVersionId()).isEqualTo(1);
        when(mapper.base(10, 3)).thenReturn(new ChangeNoteMapper.Base(2, 2));
        service.update(7, 10, 3, input("修订", null), "second");
        assertThat(row.get().baseVersionId()).isEqualTo(2);
        service.update(7, 10, 3, input(null, null), "clear");
        assertThat(row.get().text()).isNull();
        assertThat(row.get().baseVersionId()).isNull();
        assertThat(row.get().noteVersion()).isEqualTo(3);
        assertThat(row.get().corrected()).isFalse();
    }

    @Test
    void firstPostPublicationWriteNeedsNoReasonButCorrectionsDoAndRemovalIsForbidden() {
        target(true, false, "published");
        service.update(7, 10, 3, input("补写", null), "first");
        assertThat(row.get().createdAfterVisible()).isTrue();
        assertThat(row.get().corrected()).isFalse();
        rejected(input("更正", null), "REASON_REQUIRED");
        rejected(input(null, null), "NOTE_REMOVAL_NOT_ALLOWED");
        when(mapper.base(10, 3)).thenReturn(new ChangeNoteMapper.Base(2, 2));
        service.update(7, 10, 3, input("更正", "纠正笔误"), "correct");
        assertThat(row.get().corrected()).isTrue();
        assertThat(row.get().baseVersionId()).isEqualTo(1);
        assertThat(row.get().createdAfterVisible()).isTrue();
    }

    @Test
    void publishingAnotherVersionDoesNotRelabelExistingNoteBase() {
        service.update(7, 10, 3, input("相对V1", null), "draft");
        when(mapper.base(10, 3)).thenReturn(new ChangeNoteMapper.Base(2, 2));
        target(true, false, "published");
        rejected(input("新说明", null), "REASON_REQUIRED");
        service.update(7, 10, 3, input("更正历史版本说明", "补充"), "correct");
        assertThat(row.get().baseVersionId()).isEqualTo(1);
        assertThat(row.get().createdAfterVisible()).isFalse();
    }

    @Test
    void freezeMissingBaseArchivedAndUnreadableCandidatesReject() {
        target(false, true, "published");
        rejected(input("说明", null), "REVIEW_IN_PROGRESS");
        target(false, false, "archived");
        rejected(input("说明", null), "NOT_FOUND");
        target(false, false, "published");
        when(mapper.base(10, 3)).thenReturn(null);
        rejected(input("说明", null), "NO_BASE_VERSION");
        doThrow(new ChangeNoteException(ChangeNoteException.Reason.NOT_FOUND)).when(access).editable(any(), eq(10L), eq(3L));
        rejected(input("说明", null), "NOT_FOUND");
        verify(receipts, never()).insert(any());
    }

    @Test
    void concurrentVersionAndIdenticalTextRejectWithoutNewFacts() {
        store(new ChangeNoteRow(10, 3, "已有", 1L, 1, 1, false, false, null));
        rejected(new ChangeNoteInput("不同", null, 0), "NOTE_VERSION_CONFLICT");
        rejected(input("已有", null), "NO_CHANGE");
        verify(mapper, never()).appendHistory(any());
        verify(receipts, never()).insert(any());
    }

    @Test
    void matchingReplayReauthorizesAndMismatchedBindingFails() {
        var input = input("说明", null);
        var receipt = service.update(7, 10, 3, input, "request");
        var binding = commands.bind("request", 7, 10, 3, input);
        when(receipts.get(binding.scope().name(), "request")).thenReturn(new ChangeNoteCommand(binding.scope().name(),
                "request", 7, 3, 10, 0, 1, binding.digest(), commands.write(receipt)));
        assertThat(service.update(7, 10, 3, input, "request").replayed()).isTrue();
        assertThatThrownBy(() -> service.update(7, 10, 3, new ChangeNoteInput("不同", null, 0), "request"))
                .hasMessage("REQUEST_MISMATCH");
        doThrow(new ChangeNoteException(ChangeNoteException.Reason.FORBIDDEN)).when(access).editable(any(), eq(10L), eq(3L));
        assertThatThrownBy(() -> service.update(7, 10, 3, input, "request")).hasMessage("FORBIDDEN");
        verify(mapper).appendHistory(any());
        verify(receipts).insert(any());
    }

    @Test
    void historyThenAuditThenReceiptAndAuditFailureDoesNotSaveReceipt() {
        service.update(7, 10, 3, input("说明", null), "first");
        var order = inOrder(mapper, audit, receipts);
        order.verify(mapper).insert(any());
        order.verify(mapper).appendHistory(any());
        order.verify(audit).appendSuccess(any(), eq("first"), eq(7L), eq(3L), eq("update"), anyString(), anyString());
        order.verify(receipts).insert(any());
        var history = ArgumentCaptor.forClass(ChangeNoteHistory.class);
        verify(mapper).appendHistory(history.capture());
        assertThat(history.getValue().visibleToReaders()).isFalse();
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .appendSuccess(any(), eq("failed"), anyLong(), anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> service.update(7, 10, 3, input("新说明", null), "failed"))
                .isInstanceOf(IllegalStateException.class);
        verify(receipts).insert(any());
    }

    private void rejected(ChangeNoteInput input, String reason) {
        assertThatThrownBy(() -> service.update(7, 10, 3, input, "rejected")).hasMessage(reason);
    }
}
