package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CorrectionWriteServiceTest {
    @Test
    void createStoresOnlyWrongExpressionAndStableTargetAndUsesSharedWriteOrder() {
        var f = new Fixture();
        var input = input("报消", 5, null);
        var receipt = f.service.write(null, input, "create");
        assertThat(receipt).isEqualTo(new TermReceipt(TermRows.EntityKind.CORRECTION, 9, 0, 1, false));
        var order = inOrder(f.access, f.commands, f.mapper, f.audit);
        order.verify(f.access).requireWrite();
        order.verify(f.commands).bind(eq(TermRows.Operation.SEARCH_CORRECTION_CREATE), eq("create"), eq(7L), eq(null), eq(null), eq(input));
        order.verify(f.commands).lock(any());
        order.verify(f.mapper).lockState();
        order.verify(f.access).recheckWriter(7);
        order.verify(f.mapper).correctionOwner("报消");
        order.verify(f.mapper).termKeyExists("报消");
        order.verify(f.mapper).term(5);
        order.verify(f.mapper).insertCorrection(input);
        order.verify(f.mapper).advanceRevision(0);
        order.verify(f.commands).save(any(), eq(receipt));
        order.verify(f.audit).correction(any(), eq(null), any(), eq(0L), eq(1L), eq(List.of("wrongExpression", "targetTermId", "enabled")));
        verify(f.mapper, never()).insertTerm(any());
        verify(f.mapper, never()).insertAlias(anyLong(), any());
    }

    @Test
    void existingWrongKeyAndAnyCanonicalOrAliasExpressionIncludingInactiveRowsAreConflicts() {
        var own = new Fixture();
        when(own.mapper.correctionOwner("报消")).thenReturn(99L);
        failure(() -> own.service.write(null, input("报消", 5, null), "create"), TermException.Reason.EXPRESSION_CONFLICT);
        own.noBusinessWrites();
        for (String existingExpression : List.of("报销", "OA")) {
            var f = new Fixture();
            when(f.mapper.termKeyExists(TermInput.key(existingExpression))).thenReturn(true);
            failure(() -> f.service.write(null, input(existingExpression, 5, null), "create"), TermException.Reason.EXPRESSION_CONFLICT);
            f.noBusinessWrites();
            verify(f.mapper, never()).term(anyLong());
        }
    }

    @Test
    void newContentRequiresExistingEnabledTargetAndCannotPointToAnotherCorrection() {
        for (TermRows.Term target : new TermRows.Term[]{null, target(5, false, "报销")}) {
            var f = new Fixture();
            when(f.mapper.term(5)).thenReturn(target);
            failure(() -> f.service.write(null, input("报消", 5, null), "create"), TermException.Reason.TARGET_UNAVAILABLE);
            f.noBusinessWrites();
        }
        var f = new Fixture();
        when(f.mapper.term(10)).thenReturn(null);
        when(f.mapper.correction(10)).thenReturn(correction(1, true));
        failure(() -> f.service.write(null, input("报消", 10, null), "create"), TermException.Reason.TARGET_UNAVAILABLE);
        verify(f.mapper, never()).correction(10);
        f.noBusinessWrites();
    }

    @Test
    void editingWrongExpressionOrTargetRetainsOwnEnabledStateAndDoesNotCaptureTargetName() {
        var f = new Fixture();
        var before = correction(3, false);
        when(f.mapper.correction(9)).thenReturn(before);
        when(f.mapper.term(10)).thenReturn(target(10, true, "目标改名后的名称"));
        var input = input("新错词", 10, 3L);
        assertThat(f.service.write(9L, input, "edit")).isEqualTo(new TermReceipt(TermRows.EntityKind.CORRECTION, 9, 4, 1, false));
        verify(f.mapper).updateCorrection(9, input);
        var after = new TermRows.Correction(9, "新错词", "新错词", 10, false, 4, null, null);
        verify(f.audit).correction(any(), eq(before), eq(after), eq(0L), eq(1L), eq(List.of("wrongExpression", "targetTermId")));
        verify(f.mapper, never()).enableCorrection(anyLong(), any());
        assertThat(after.available(target(10, true, "再次改名"))).isFalse();
        assertThat(new TermRows.Correction(9, "报消", "报消", 5, true, 0, null, null).available(target(5, true, "报销新名称"))).isTrue();
    }

    @Test
    void inactiveTargetStillAllowsIndependentDisableButReenableRequiresActiveTarget() {
        var f = new Fixture();
        when(f.mapper.correction(9)).thenReturn(correction(3, true));
        when(f.mapper.term(5)).thenReturn(target(5, false, "报销"));
        var disable = new TermInputs.EnabledWrite(false, 3);
        assertThat(f.service.enabled(9, disable, "disable")).isEqualTo(new TermReceipt(TermRows.EntityKind.CORRECTION, 9, 4, 1, false));
        verify(f.mapper, never()).term(anyLong());
        verify(f.mapper, never()).updateCorrection(anyLong(), any());
        verify(f.audit).correction(any(), eq(correction(3, true)), eq(correction(4, false)), eq(0L), eq(1L), eq(List.of("enabled")));
        var inactive = new Fixture();
        when(inactive.mapper.correction(9)).thenReturn(correction(4, false));
        when(inactive.mapper.term(5)).thenReturn(target(5, false, "报销"));
        failure(() -> inactive.service.enabled(9, new TermInputs.EnabledWrite(true, 4), "enable"), TermException.Reason.TARGET_UNAVAILABLE);
        inactive.noBusinessWrites();
        var mapping = correction(4, false);
        assertThat(mapping.available(target(5, false, "报销"))).isFalse();
        assertThat(mapping.available(target(5, true, "报销"))).isFalse();
        assertThat(correction(4, true).available(target(5, true, "报销"))).isTrue();
        assertThat(correction(4, true).available(target(10, true, "其他"))).isFalse();
    }

    @Test
    void noChangeStaleOrMalformedInputMissingTargetAndOverflowNeverWrite() {
        var f = new Fixture();
        when(f.mapper.correction(9)).thenReturn(correction(3, true));
        failure(() -> f.service.write(9L, input("报消", 5, 3L), "same"), TermException.Reason.NO_CHANGE);
        failure(() -> f.service.write(9L, input("新错词", 5, 2L), "stale"), TermException.Reason.VERSION_CONFLICT);
        failure(() -> f.service.write(null, input("\uD800", 5, null), "bad"), TermException.Reason.INVALID_INPUT);
        f.noBusinessWrites();
        var missing = new Fixture();
        failure(() -> missing.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"), TermException.Reason.NOT_FOUND);
        missing.noBusinessWrites();
        var overflow = new Fixture();
        when(overflow.mapper.lockState()).thenReturn(Long.MAX_VALUE);
        failure(() -> overflow.service.write(null, input("报消", 5, null), "create"), TermException.Reason.UNAVAILABLE);
        overflow.noBusinessWrites();
    }

    @Test
    void replayAndLockRevocationDoNotReadCurrentMappingOrTargetOrRepeatBusinessWrites() {
        var f = new Fixture();
        var row = new TermRows.Command("edit", TermRows.Operation.SEARCH_CORRECTION_UPDATE, 7, TermRows.EntityKind.CORRECTION,
                9L, 3L, 1, "a".repeat(64), "{}", null);
        when(f.commands.lock(any())).thenReturn(row);
        when(f.commands.receipt(row)).thenReturn(new TermReceipt(TermRows.EntityKind.CORRECTION, 9, 4, 12, false));
        assertThat(f.service.write(9L, input("新错词", 5, 3L), "edit").replayed()).isTrue();
        verify(f.access).recheckWriter(7);
        verify(f.mapper, never()).correction(anyLong());
        verify(f.mapper, never()).term(anyLong());
        f.noBusinessWrites();
        var revoked = new Fixture();
        doThrow(new TermException(TermException.Reason.FORBIDDEN)).when(revoked.access).recheckWriter(7);
        failure(() -> revoked.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"), TermException.Reason.FORBIDDEN);
        verify(revoked.mapper, never()).correction(anyLong());
        verify(revoked.mapper, never()).term(anyLong());
        revoked.noBusinessWrites();
    }

    private static TermRows.Term target(long id, boolean enabled, String name) {
        return new TermRows.Term(id, name, name, "释义", enabled, 0, null, null);
    }

    private static TermRows.Correction correction(long version, boolean enabled) {
        return new TermRows.Correction(9, "报消", "报消", 5, enabled, version, null, null);
    }

    private static TermInputs.CorrectionWrite input(String wrong, long target, Long version) {
        return new TermInputs.CorrectionWrite(wrong, TermInput.key(wrong), target, version);
    }

    private static void failure(Runnable action, TermException.Reason expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(expected));
    }

    private static final class Fixture {
        private final TermDatabase database = mock(TermDatabase.class);
        private final TermAccess access = mock(TermAccess.class);
        private final TermMapper mapper = mock(TermMapper.class);
        private final TermCommands commands = mock(TermCommands.class);
        private final TermAudit audit = mock(TermAudit.class);
        private final CorrectionWriteService service = new CorrectionWriteService(database, access, mapper, commands, audit);

        private Fixture() {
            when(database.write(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
            when(access.requireWrite()).thenReturn(7L);
            when(commands.bind(any(), anyString(), anyLong(), any(), any(), any())).thenAnswer(invocation -> {
                TermRows.Operation operation = invocation.getArgument(0);
                return new TermCommands.Binding(operation, invocation.getArgument(1), invocation.getArgument(2), operation.kind(),
                        invocation.getArgument(3), invocation.getArgument(4), "a".repeat(64));
            });
            when(mapper.lockState()).thenReturn(0L);
            when(mapper.correctionOwner(anyString())).thenReturn(null);
            when(mapper.term(5)).thenReturn(target(5, true, "报销"));
            when(mapper.insertCorrection(any())).thenAnswer(invocation -> {
                TermInputs.CorrectionWrite input = invocation.getArgument(0);
                return new TermRows.Correction(9, input.wrongExpression(), input.wrongKey(), input.termId(), true, 0, null, null);
            });
            when(mapper.updateCorrection(anyLong(), any())).thenReturn(1);
            when(mapper.enableCorrection(anyLong(), any())).thenReturn(1);
            when(mapper.advanceRevision(anyLong())).thenReturn(1);
        }

        private void noBusinessWrites() {
            verify(mapper, never()).insertCorrection(any());
            verify(mapper, never()).updateCorrection(anyLong(), any());
            verify(mapper, never()).enableCorrection(anyLong(), any());
            verify(mapper, never()).advanceRevision(anyLong());
            verify(commands, never()).save(any(), any());
            verifyNoInteractions(audit);
        }
    }
}
