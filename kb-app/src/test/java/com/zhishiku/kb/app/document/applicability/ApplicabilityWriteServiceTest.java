package com.zhishiku.kb.app.document.applicability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.mapper.ValidityCommandMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityTarget;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityUpdate;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.applicability.model.ValidityCommandRow;
import com.zhishiku.kb.app.document.applicability.service.ApplicabilityWriteService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityCommands;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.APPLICABILITY_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REASON_REQUIRED;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REQUEST_MISMATCH;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REVIEW_IN_PROGRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicabilityWriteServiceTest {
    private final ApplicabilityMapper mapper = mock(ApplicabilityMapper.class);
    private final ValidityCommandMapper commandMapper = mock(ValidityCommandMapper.class);
    private final ValidityAccess access = mock(ValidityAccess.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final BusinessDate date = mock(BusinessDate.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final ValidityCommands commands = new ValidityCommands(commandMapper, new ObjectMapper());
    private final ApplicabilityWriteService service = new ApplicabilityWriteService(mapper, access, commands, audit, date);
    private final AtomicReference<ApplicabilityRow> current = new AtomicReference<>();
    private final AtomicReference<ValidityCommandRow> receipt = new AtomicReference<>();
    private final ApplicabilityUpdate input = new ApplicabilityUpdate(null, UntilKind.DATE, LocalDate.of(2026, 12, 31), null, 0);

    @BeforeEach
    void setup() {
        current.set(new ApplicabilityRow(10, 20, null, UntilKind.UNDECLARED, null, 0, false, null, true));
        when(access.current(7)).thenReturn(handle);
        when(mapper.lockVersion(10, 20)).thenReturn(target("REVIEW_V1", "NONE", false, false, "active"));
        when(mapper.target(10, 20)).thenAnswer(call -> mapper.lockVersion(10, 20));
        when(mapper.get(10, 20)).thenAnswer(call -> current.get());
        when(date.today()).thenReturn(LocalDate.of(2026, 11, 15));
        when(mapper.insert(any())).thenAnswer(call -> saveRow(call.getArgument(0)));
        when(mapper.update(any())).thenAnswer(call -> saveRow(call.getArgument(0)));
        when(mapper.appendChange(any())).thenReturn(1);
        when(commandMapper.get("DOCUMENT_APPLICABILITY_UPDATE", "request")).thenAnswer(call -> receipt.get());
        when(commandMapper.insert(any())).thenAnswer(call -> {
            receipt.set(call.getArgument(0));
            return 1;
        });
    }

    private int saveRow(ApplicabilityRow row) {
        current.set(new ApplicabilityRow(row.documentId(), row.versionId(), row.effectiveFrom(), row.untilKind(), row.effectiveUntil(),
                row.applicabilityVersion(), row.corrected(), OffsetDateTime.parse("2026-11-15T00:00:00Z"), row.current()));
        return 1;
    }

    @Test
    void locksRequestThenVersionAndReauthorizesBeforeHistoryAuditAndReceipt() {
        assertThat(service.update(7, 10, 20, input, "request").applicabilityVersion()).isEqualTo(1);
        var order = inOrder(commandMapper, mapper, access, audit);
        order.verify(commandMapper).lockRequest("document-validity:DOCUMENT_APPLICABILITY_UPDATE:request");
        order.verify(mapper).lockVersion(10, 20);
        order.verify(access).current(7);
        order.verify(access).editable(handle, 10, 20);
        order.verify(mapper).insert(any());
        order.verify(mapper).appendChange(any());
        order.verify(audit).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        order.verify(commandMapper).insert(any());
    }

    @Test
    void hiddenCandidateFrozenReviewAndArchivedTargetsNeverWrite() {
        doThrow(new ValidityException(NOT_FOUND)).when(access).editable(handle, 10, 20);
        reason(NOT_FOUND, input);
        org.mockito.Mockito.doNothing().when(access).editable(handle, 10, 20);
        when(mapper.lockVersion(10, 20)).thenReturn(target("REVIEW_V1", "NONE", true, false, "active"));
        reason(REVIEW_IN_PROGRESS, input);
        when(mapper.lockVersion(10, 20)).thenReturn(target("REVIEW_V1", "PUBLISHED", false, true, "active"));
        reason(NOT_FOUND, input);
        when(mapper.lockVersion(10, 20)).thenReturn(target("REVIEW_V1", "PUBLISHED", false, false, "archived"));
        reason(NOT_FOUND, input);
        when(mapper.lockVersion(10, 20)).thenReturn(null);
        reason(NOT_FOUND, input);
        verify(mapper, never()).insert(any());
        verify(commandMapper, never()).insert(any());
    }

    @Test
    void publicationRaceRequiresReasonAndAllPublishedEvidenceAllowsImmediateHistoricalCorrection() {
        for (String evidence : java.util.List.of("PUBLISHED", "OBSERVED_CURRENT")) {
            receipt.set(null);
            current.set(new ApplicabilityRow(10, 20, null, UntilKind.UNDECLARED, null, 0, false, null, false));
            when(mapper.lockVersion(10, 20)).thenReturn(target("REVIEW_V1", evidence, false, false, "active"));
            reason(REASON_REQUIRED, input);
            var result = service.update(7, 10, 20, new ApplicabilityUpdate(null, UntilKind.DATE, input.effectiveUntil(), "更正原因", 0), "request");
            assertThat(result.corrected()).isTrue();
            assertThat(current.get().current()).isFalse();
        }
        receipt.set(null);
        current.set(new ApplicabilityRow(10, 20, null, UntilKind.UNDECLARED, null, 0, false, null, true));
        when(mapper.lockVersion(10, 20)).thenReturn(target("LEGACY_COMPAT", "NONE", false, false, "active"));
        reason(REASON_REQUIRED, input);
    }

    @Test
    void matchingReplayDoesNotWriteAgainAndRevocationOrChangedBindingRejectsReplay() {
        var first = service.update(7, 10, 20, input, "request");
        assertThat(service.update(7, 10, 20, input, "request")).isEqualTo(first.replay());
        verify(mapper).appendChange(any());
        reason(REQUEST_MISMATCH, new ApplicabilityUpdate(null, UntilKind.OPEN_ENDED, null, null, 0));
        doThrow(new ValidityException(FORBIDDEN)).when(access).editable(handle, 10, 20);
        reason(FORBIDDEN, input);
    }

    @Test
    void optimisticConflictAndNoChangeNeverAcknowledgeSuccess() {
        reason(NO_CHANGE, new ApplicabilityUpdate(null, UntilKind.UNDECLARED, null, null, 0));
        reason(APPLICABILITY_VERSION_CONFLICT, new ApplicabilityUpdate(null, UntilKind.DATE, input.effectiveUntil(), null, 1));
        org.mockito.Mockito.doReturn(0).when(mapper).insert(any());
        reason(APPLICABILITY_VERSION_CONFLICT, input);
        verify(audit, never()).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void auditFailureDoesNotCallReceiptSaveOrExposeCauseInStoredSummary() {
        doThrow(new IllegalStateException("private-audit-details")).when(audit).appendSuccess(any(), any(), anyLong(), anyLong(), any(), any(), any());
        assertThatThrownBy(() -> service.update(7, 10, 20, input, "request")).isInstanceOf(IllegalStateException.class);
        verify(commandMapper, never()).insert(any());
        assertThat(receipt.get()).isNull();
    }

    @Test
    void competingRequestBasedOnSameVersionCannotCommitAfterFirst() {
        service.update(7, 10, 20, input, "request");
        assertThatThrownBy(() -> service.update(7, 10, 20,
                new ApplicabilityUpdate(null, UntilKind.OPEN_ENDED, null, null, 0), "other-request"))
                .isInstanceOfSatisfying(ValidityException.class,
                        error -> assertThat(error.reason()).isEqualTo(APPLICABILITY_VERSION_CONFLICT));
        verify(mapper).insert(any());
        verify(mapper).appendChange(any());
    }

    private void reason(ValidityException.Reason reason, ApplicabilityUpdate update) {
        assertThatThrownBy(() -> service.update(7, 10, 20, update, "request"))
                .isInstanceOfSatisfying(ValidityException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }

    private static ApplicabilityTarget target(String protocol, String evidence, boolean reviewing, boolean archived, String status) {
        return new ApplicabilityTarget(10, 20, 2, status, archived, protocol, evidence, reviewing);
    }
}
