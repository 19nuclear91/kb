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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermWriteServiceTest {
    @Test
    void createAllowsSharedAliasesAndCanonicalNameMatchingAnotherGroupsAlias() {
        var f = new Fixture();
        var input = input("项目管理", null, new TermInputs.Alias("PM", "pm", TermRows.AliasKind.ABBREVIATION));
        var receipt = f.service.write(null, input, "create");
        assertThat(receipt).isEqualTo(new TermReceipt(TermRows.EntityKind.TERM, 9, 0, 1, false));
        var order = inOrder(f.access, f.commands, f.mapper, f.audit);
        order.verify(f.access).requireWrite();
        order.verify(f.commands).bind(eq(TermRows.Operation.SEARCH_TERM_CREATE), eq("create"), eq(7L), eq(null), eq(null), any());
        order.verify(f.commands).lock(any());
        order.verify(f.mapper).lockState();
        order.verify(f.access).recheckWriter(7);
        order.verify(f.mapper).canonicalOwner("项目管理");
        order.verify(f.mapper).correctionKeyExists("项目管理");
        order.verify(f.mapper).correctionKeyExists("pm");
        order.verify(f.mapper).insertTerm(input);
        order.verify(f.mapper).insertAlias(9, input.aliases().getFirst());
        order.verify(f.mapper).advanceRevision(0);
        order.verify(f.commands).save(any(), eq(receipt));
        order.verify(f.audit).term(any(), eq(null), any(), eq(0L), eq(1L), eq(List.of("canonicalName", "description", "aliases", "enabled")));
    }

    @Test
    void canonicalOwnerIncludingDisabledGroupsAndWrongExpressionConflictsRejectBeforeWrites() {
        var f = new Fixture();
        when(f.mapper.canonicalOwner("名称")).thenReturn(99L);
        failure(() -> f.service.write(null, input("名称", null), "create"), TermException.Reason.NAME_CONFLICT);
        f.noBusinessWrites();
        for (String conflict : List.of("名称", "pm")) {
            var other = new Fixture();
            when(other.mapper.correctionKeyExists(conflict)).thenReturn(true);
            failure(() -> other.service.write(null, input("名称", null,
                    new TermInputs.Alias("PM", "pm", TermRows.AliasKind.ABBREVIATION)), "create"), TermException.Reason.EXPRESSION_CONFLICT);
            other.noBusinessWrites();
        }
    }

    @Test
    void completeEditKeepsIdentityAndEnabledStateAndReplacesAliasSetOnlyOnce() {
        var f = new Fixture();
        var before = term(3, false);
        when(f.mapper.term(9)).thenReturn(before);
        when(f.mapper.aliases(9)).thenReturn(List.of(new TermRows.Alias(9, "旧别名", "旧别名", TermRows.AliasKind.FORMER_NAME)));
        var input = input("新名称", 3L, new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION));
        var receipt = f.service.write(9L, input, "edit");
        assertThat(receipt).isEqualTo(new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 1, false));
        verify(f.mapper).updateTerm(9, input);
        verify(f.mapper).clearAliases(9);
        verify(f.mapper).insertAlias(9, input.aliases().getFirst());
        verify(f.mapper, never()).enableTerm(anyLong(), any());
        verify(f.audit).term(any(), eq(before), eq(new TermRows.Term(9, "新名称", "新名称", "释义", false, 4, null, null)),
                eq(0L), eq(1L), eq(List.of("canonicalName", "aliases")));
    }

    @Test
    void equivalentAliasOrderNoChangeStaleVersionAndDuplicateInputsDoNotWrite() {
        var f = new Fixture();
        when(f.mapper.term(9)).thenReturn(term(3, true));
        var a = new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION);
        var b = new TermInputs.Alias("旧称", "旧称", TermRows.AliasKind.FORMER_NAME);
        when(f.mapper.aliases(9)).thenReturn(List.of(new TermRows.Alias(9, b.text(), b.key(), b.kind()), new TermRows.Alias(9, a.text(), a.key(), a.kind())));
        failure(() -> f.service.write(9L, input("名称", 3L, b, a), "same"), TermException.Reason.NO_CHANGE);
        failure(() -> f.service.write(9L, input("名称", 2L), "stale"), TermException.Reason.VERSION_CONFLICT);
        clearInvocations(f.mapper, f.commands);
        failure(() -> f.service.write(9L, input("名称", 3L, a,
                new TermInputs.Alias(" oa ", "伪造键", TermRows.AliasKind.SYNONYM)), "duplicate"), TermException.Reason.INVALID_INPUT);
        verifyNoInteractions(f.mapper, f.commands);
        f.noBusinessWrites();
    }

    @Test
    void independentDisableDoesNotTouchNamesAliasesOrCorrections() {
        var f = new Fixture();
        when(f.mapper.term(9)).thenReturn(term(3, true));
        var input = new TermInputs.EnabledWrite(false, 3);
        var receipt = f.service.enabled(9, input, "disable");
        assertThat(receipt.version()).isEqualTo(4);
        assertThat(receipt.dictionaryRevision()).isEqualTo(1);
        verify(f.mapper).enableTerm(9, input);
        verify(f.mapper, never()).updateTerm(anyLong(), any());
        verify(f.mapper, never()).clearAliases(anyLong());
        verify(f.mapper, never()).aliases(anyLong());
        verify(f.mapper, never()).correction(anyLong());
        verify(f.audit).term(any(), eq(term(3, true)), eq(term(4, false)), eq(0L), eq(1L), eq(List.of("enabled")));
        var unchanged = new Fixture();
        when(unchanged.mapper.term(9)).thenReturn(term(3, false));
        failure(() -> unchanged.service.enabled(9, input, "same"), TermException.Reason.NO_CHANGE);
        unchanged.noBusinessWrites();
    }

    @Test
    void revokedInitialOrLockQualificationDoesNotReadTargetOrWriteAnything() {
        var f = new Fixture();
        doThrow(new TermException(TermException.Reason.FORBIDDEN)).when(f.access).recheckWriter(7);
        failure(() -> f.service.write(9L, input("新名称", 3L), "edit"), TermException.Reason.FORBIDDEN);
        verify(f.mapper).lockState();
        verify(f.mapper, never()).term(anyLong());
        f.noBusinessWrites();
        var initial = new Fixture();
        when(initial.access.requireWrite()).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        failure(() -> initial.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"), TermException.Reason.FORBIDDEN);
        verifyNoInteractions(initial.mapper, initial.commands, initial.audit);
    }

    @Test
    void overflowOrMissingStateAndTargetAreExplicitFailuresWithoutVersionWraparound() {
        var revision = new Fixture();
        when(revision.mapper.lockState()).thenReturn(Long.MAX_VALUE);
        failure(() -> revision.service.write(null, input("名称", null), "create"), TermException.Reason.UNAVAILABLE);
        revision.noBusinessWrites();
        var version = new Fixture();
        when(version.mapper.term(9)).thenReturn(term(Long.MAX_VALUE, true));
        failure(() -> version.service.enabled(9, new TermInputs.EnabledWrite(false, Long.MAX_VALUE), "disable"), TermException.Reason.UNAVAILABLE);
        version.noBusinessWrites();
        var missing = new Fixture();
        when(missing.mapper.lockState()).thenReturn(null);
        failure(() -> missing.service.write(null, input("名称", null), "create"), TermException.Reason.UNAVAILABLE);
        missing.noBusinessWrites();
        var noTarget = new Fixture();
        failure(() -> noTarget.service.write(9L, input("名称", 3L), "edit"), TermException.Reason.NOT_FOUND);
        noTarget.noBusinessWrites();
    }

    @Test
    void replayRechecksCurrentQualificationAndReturnsOriginalVersionWithoutLookingUpCurrentEntity() {
        var f = new Fixture();
        var previous = new TermRows.Command("edit", TermRows.Operation.SEARCH_TERM_UPDATE, 7, TermRows.EntityKind.TERM,
                9L, 3L, 1, "a".repeat(64), "{}", null);
        when(f.commands.lock(any())).thenReturn(previous);
        when(f.mapper.lockState()).thenReturn(Long.MAX_VALUE);
        when(f.commands.receipt(previous)).thenReturn(new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 12, false));
        assertThat(f.service.write(9L, input("新名称", 3L), "edit"))
                .isEqualTo(new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 12, true));
        verify(f.access).recheckWriter(7);
        verify(f.mapper, never()).term(anyLong());
        f.noBusinessWrites();
    }

    @Test
    void failedEntityOrAliasWriteCannotAppendSuccessfulReceiptOrAudit() {
        var f = new Fixture();
        when(f.mapper.term(9)).thenReturn(term(3, true));
        when(f.mapper.updateTerm(anyLong(), any())).thenReturn(0);
        failure(() -> f.service.write(9L, input("新名称", 3L), "edit"), TermException.Reason.VERSION_CONFLICT);
        verify(f.mapper, never()).advanceRevision(anyLong());
        verify(f.commands, never()).save(any(), any());
        verifyNoInteractions(f.audit);
        var alias = new Fixture();
        when(alias.mapper.insertAlias(anyLong(), any())).thenReturn(0);
        failure(() -> alias.service.write(null, input("名称", null,
                new TermInputs.Alias("OA", "oa", TermRows.AliasKind.ABBREVIATION)), "create"), TermException.Reason.UNAVAILABLE);
        verify(alias.mapper, never()).advanceRevision(anyLong());
        verify(alias.commands, never()).save(any(), any());
        verifyNoInteractions(alias.audit);
    }

    private static TermInputs.TermWrite input(String name, Long version, TermInputs.Alias... aliases) {
        return new TermInputs.TermWrite(name, name, "释义", List.of(aliases), version);
    }

    private static TermRows.Term term(long version, boolean enabled) {
        return new TermRows.Term(9, "名称", "名称", "释义", enabled, version, null, null);
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
        private final TermWriteService service = new TermWriteService(database, access, mapper, commands, audit);

        private Fixture() {
            when(database.write(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
            when(access.requireWrite()).thenReturn(7L);
            when(commands.bind(any(), anyString(), anyLong(), any(), any(), any())).thenAnswer(invocation -> {
                TermRows.Operation operation = invocation.getArgument(0);
                return new TermCommands.Binding(operation, invocation.getArgument(1), invocation.getArgument(2), operation.kind(),
                        invocation.getArgument(3), invocation.getArgument(4), "a".repeat(64));
            });
            when(mapper.lockState()).thenReturn(0L);
            when(mapper.canonicalOwner(anyString())).thenReturn(null);
            when(mapper.insertTerm(any())).thenAnswer(invocation -> {
                TermInputs.TermWrite input = invocation.getArgument(0);
                return new TermRows.Term(9, input.canonicalName(), input.canonicalKey(), input.description(), true, 0, null, null);
            });
            when(mapper.updateTerm(anyLong(), any())).thenReturn(1);
            when(mapper.enableTerm(anyLong(), any())).thenReturn(1);
            when(mapper.insertAlias(anyLong(), any())).thenReturn(1);
            when(mapper.advanceRevision(anyLong())).thenReturn(1);
        }

        private void noBusinessWrites() {
            verify(mapper, never()).insertTerm(any());
            verify(mapper, never()).updateTerm(anyLong(), any());
            verify(mapper, never()).enableTerm(anyLong(), any());
            verify(mapper, never()).clearAliases(anyLong());
            verify(mapper, never()).insertAlias(anyLong(), any());
            verify(mapper, never()).advanceRevision(anyLong());
            verify(commands, never()).save(any(), any());
            verifyNoInteractions(audit);
        }
    }
}
