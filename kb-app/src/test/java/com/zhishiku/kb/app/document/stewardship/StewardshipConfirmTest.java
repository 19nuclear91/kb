package com.zhishiku.kb.app.document.stewardship;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipDocument;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.service.StewardshipConfirmService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class StewardshipConfirmTest {
    private final StewardshipFixture f = new StewardshipFixture();
    private final ValidityAccess access = mock(ValidityAccess.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final StewardshipConfirmService confirms = new StewardshipConfirmService(f.mapper, access, f.commands, f.audit, f.date);

    private void assign() {
        f.writes.update(7, 10, new StewardshipInputs.Update(8L, 3, 0), "assign");
        when(access.current(8)).thenReturn(handle);
        when(access.has(handle, 10, PermissionService.PERM_VIEW)).thenReturn(true);
    }

    @Test
    void onlyCurrentResponsibleCanConfirmAndManagementOrEditDoesNotSubstitute() {
        assign();
        for (long actor : java.util.List.of(7L, 9L)) {
            assertThatThrownBy(() -> confirms.confirm(actor, 10, new StewardshipInputs.Confirm(1), "other-" + actor))
                    .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason().status()).isEqualTo(403));
        }
        assertThat(f.current.get().lastConfirmedOn()).isNull();
    }

    @Test
    void earlyConfirmationRecalculatesDueDateAndPreservesFirstAssignment() {
        assign();
        when(f.date.today()).thenReturn(LocalDate.of(2026, 12, 12));
        var result = confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm");
        assertThat(result.lastConfirmedOn()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(result.dueDate()).isEqualTo(LocalDate.of(2027, 3, 12));
        assertThat(f.current.get().firstAssignedOn()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(f.current.get().lastConfirmedBy()).isEqualTo(8L);
    }

    @Test
    void reassignmentAndRevocationRejectOldResponsibleEvenForMatchingReplay() {
        assign();
        confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm");
        when(f.mapper.eligible(10, 8)).thenReturn(false);
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason().status()).isEqualTo(403));
        when(f.mapper.eligible(10, 8)).thenReturn(true);
        f.writes.update(7, 10, new StewardshipInputs.Update(9L, 3, 2), "reassign");
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason().status()).isEqualTo(403));
    }

    @Test
    void archivalAndDeletedDocumentsRejectConfirmationAndPublicationAloneDoesNotConfirm() {
        assign();
        var before = f.current.get();
        // 发布流程不调用复审写入；文档读取事实改变不构成确认。
        when(f.mapper.document(10)).thenReturn(new StewardshipDocument(10, 2, "active", false));
        assertThat(f.current.get()).isEqualTo(before);
        assertThat(before.lastConfirmedOn()).isNull();
        when(f.mapper.document(10)).thenReturn(new StewardshipDocument(10, 2, "active", true));
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "archived")).isInstanceOf(ValidityException.class);
        when(f.mapper.document(10)).thenReturn(new StewardshipDocument(10, 2, "archived", false));
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "archived-doc")).isInstanceOf(ValidityException.class);
        when(f.mapper.shareDocument(10)).thenReturn(null);
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "deleted"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason().status()).isEqualTo(404));
    }

    @Test
    void confirmationAndAssignmentBasedOnSameVersionCannotBothSucceed() {
        assign();
        confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm");
        assertThatThrownBy(() -> f.writes.update(7, 10, new StewardshipInputs.Update(9L, 3, 1), "competing"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.STEWARDSHIP_VERSION_CONFLICT));
        assertThat(confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm").replayed()).isTrue();
    }

    @Test
    void assignmentWinningBeforeConfirmationReauthorizesBeforeCheckingStaleVersion() {
        assign();
        f.writes.update(7, 10, new StewardshipInputs.Update(9L, 3, 1), "competing-reassign");
        var before = f.current.get();
        // 直接驱动锁等待后的合法串行化顺序，不模拟真实数据库并发。
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "competing-confirm"))
                .isInstanceOfSatisfying(ValidityException.class,
                        error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.FORBIDDEN));
        assertThat(f.current.get()).isEqualTo(before);
        assertThat(before.responsibleUserId()).isEqualTo(9L);
        assertThat(before.lastConfirmedOn()).isNull();
        assertThat(f.receipts).doesNotContainKey("DOCUMENT_STEWARDSHIP_CONFIRM:competing-confirm");
    }

    @Test
    void stillEligibleResponsibleGetsConflictForAStaleNewRequestButMatchingReplayKeepsItsReceipt() {
        assign();
        var first = confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm");
        var before = f.current.get();
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "other-confirm"))
                .isInstanceOfSatisfying(ValidityException.class,
                        error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.STEWARDSHIP_VERSION_CONFLICT));
        assertThat(f.current.get()).isEqualTo(before);
        assertThat(confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm")).isEqualTo(first.replay());
        assertThat(f.receipts).doesNotContainKey("DOCUMENT_STEWARDSHIP_CONFIRM:other-confirm");
    }

    @Test
    void revocationAndInaccessibleTargetRejectBeforeVersionOrReceiptDisclosure() {
        assign();
        confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), "confirm");
        var before = f.current.get();
        when(f.mapper.eligible(10, 8)).thenReturn(false);
        assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(0), "revoked-new"))
                .isInstanceOfSatisfying(ValidityException.class,
                        error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.FORBIDDEN));
        when(f.mapper.eligible(10, 8)).thenReturn(true);
        when(access.has(handle, 10, PermissionService.PERM_VIEW)).thenReturn(false);
        for (String request : java.util.List.of("confirm", "inaccessible-new")) {
            assertThatThrownBy(() -> confirms.confirm(8, 10, new StewardshipInputs.Confirm(1), request))
                    .isInstanceOfSatisfying(ValidityException.class,
                            error -> assertThat(error.reason()).isEqualTo(ValidityException.Reason.NOT_FOUND));
        }
        assertThat(f.current.get()).isEqualTo(before);
        assertThat(f.receipts).doesNotContainKeys("DOCUMENT_STEWARDSHIP_CONFIRM:revoked-new", "DOCUMENT_STEWARDSHIP_CONFIRM:inaccessible-new");
        assertThat(f.receipts).hasSize(2);
        verify(f.mapper).update(any());
    }
}
