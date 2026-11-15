package com.zhishiku.kb.app.document.stewardship;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StewardshipAssignmentTest {
    @Test
    void assignmentReassignmentCancellationAndReassignmentKeepFirstDateAndUseDistinctAuditOperations() {
        var f = new StewardshipFixture();
        f.writes.update(7, 10, new StewardshipInputs.Update(8L, 3, 0), "assign");
        var first = f.current.get();
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        f.writes.update(7, 10, new StewardshipInputs.Update(9L, 3, 1), "reassign");
        assertThat(f.current.get().dueDate()).isEqualTo(first.dueDate());
        f.writes.update(7, 10, new StewardshipInputs.Update(null, null, 2), "unassign");
        assertThat(f.current.get().responsibleUserId()).isNull();
        assertThat(f.current.get().dueDate()).isEqualTo(first.dueDate());
        f.writes.update(7, 10, new StewardshipInputs.Update(8L, 3, 3), "assign-again");
        assertThat(f.current.get().firstAssignedOn()).isEqualTo(first.firstAssignedOn());
        assertThat(f.current.get().dueDate()).isEqualTo(first.dueDate());
        f.writes.update(7, 10, new StewardshipInputs.Update(8L, 6, 4), "cycle");
        assertThat(f.current.get().dueDate()).isEqualTo(LocalDate.of(2027, 5, 30));
        for (String operation : java.util.List.of("assign", "reassign", "unassign", "cycle")) {
            verify(f.audit).appendSuccess(eq(AuditScope.DOCUMENT_STEWARDSHIP_UPDATE), eq(operation), eq(7L), eq(10L), eq(operation), any(), any());
        }
        var order = inOrder(f.commandMapper, f.mapper, f.access, f.audit);
        order.verify(f.commandMapper).lockRequest("document-validity:DOCUMENT_STEWARDSHIP_UPDATE:assign");
        order.verify(f.mapper).shareDocument(10);
        order.verify(f.access).manager(7, 10, true);
        order.verify(f.mapper).lock(10);
        order.verify(f.mapper).eligible(10, 8);
        order.verify(f.mapper).insert(any());
        order.verify(f.audit).appendSuccess(any(), eq("assign"), anyLong(), anyLong(), any(), any(), any());
        order.verify(f.commandMapper).insert(any());
    }

    @Test
    void candidateLossAndMissingManagementOrArchivedAndDeletedTargetsNeverChangeAssignment() {
        var f = new StewardshipFixture();
        when(f.mapper.eligible(10, 8)).thenReturn(false);
        reason(f, ValidityException.Reason.STEWARD_INELIGIBLE, new StewardshipInputs.Update(8L, 3, 0));
        doThrow(new ValidityException(ValidityException.Reason.NOT_FOUND)).when(f.access).manager(7, 10, true);
        reason(f, ValidityException.Reason.NOT_FOUND, new StewardshipInputs.Update(8L, 3, 0));
        doThrow(new ValidityException(ValidityException.Reason.SPACE_ARCHIVED)).when(f.access).manager(7, 10, true);
        reason(f, ValidityException.Reason.SPACE_ARCHIVED, new StewardshipInputs.Update(8L, 3, 0));
        when(f.mapper.shareDocument(10)).thenReturn(null);
        reason(f, ValidityException.Reason.NOT_FOUND, new StewardshipInputs.Update(8L, 3, 0));
        assertThat(f.current.get()).isNull();
        verify(f.commandMapper, never()).insert(any());
    }

    @Test
    void managerCanAssignSelfAndNoChangeOrStaleVersionHasNoSuccessEvent() {
        var f = new StewardshipFixture();
        f.writes.update(7, 10, new StewardshipInputs.Update(7L, 12, 0), "self");
        assertThat(f.current.get().responsibleUserId()).isEqualTo(7L);
        reason(f, ValidityException.Reason.NO_CHANGE, new StewardshipInputs.Update(7L, 12, 1));
        reason(f, ValidityException.Reason.STEWARDSHIP_VERSION_CONFLICT, new StewardshipInputs.Update(8L, 12, 0));
    }

    @Test
    void cycleChangeUsesLastConfirmationAndMatchingReplayReauthorizesButNeverWritesAgain() {
        var f = new StewardshipFixture();
        f.current.set(new StewardshipRow(10, 8L, 12, LocalDate.of(2025, 3, 10), LocalDate.of(2026, 3, 10), 8L,
                LocalDate.of(2027, 3, 10), 1, null));
        var input = new StewardshipInputs.Update(8L, 6, 1);
        var first = f.writes.update(7, 10, input, "request");
        assertThat(first.dueDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(f.writes.update(7, 10, input, "request")).isEqualTo(first.replay());
        verify(f.mapper).update(any());
        assertThatThrownBy(() -> f.writes.update(7, 10, new StewardshipInputs.Update(9L, 6, 1), "request"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.REQUEST_MISMATCH));
        doThrow(new ValidityException(ValidityException.Reason.NOT_FOUND)).when(f.access).manager(7, 10, true);
        assertThatThrownBy(() -> f.writes.update(7, 10, input, "request")).isInstanceOf(ValidityException.class);
    }

    @Test
    void auditFailureDoesNotSaveReceipt() {
        var f = new StewardshipFixture();
        doThrow(new IllegalStateException("private-audit")).when(f.audit).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThatThrownBy(() -> f.writes.update(7, 10, new StewardshipInputs.Update(8L, 3, 0), "request")).isInstanceOf(IllegalStateException.class);
        verify(f.commandMapper, never()).insert(any());
    }

    private static void reason(StewardshipFixture f, ValidityException.Reason reason, StewardshipInputs.Update input) {
        assertThatThrownBy(() -> f.writes.update(7, 10, input, "error-request"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }
}
