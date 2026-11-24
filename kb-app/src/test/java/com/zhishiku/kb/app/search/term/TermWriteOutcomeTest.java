package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 事务代理替身只记录提交及回滚请求，不模拟或证明真实数据库原子性。 */
class TermWriteOutcomeTest {
    @Test
    void entityRevisionReceiptAndAuditUseOneWriteCallbackInThatOrder() {
        var f = new Fixture();
        assertThat(f.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"))
                .isEqualTo(new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 11, false));
        assertThat(f.events).containsExactly("begin", "entity", "revision", "receipt", "audit", "commit-request");
    }

    @Test
    void receiptAndAuditFailurePropagateWithoutReturningSuccessfulResult() {
        var receipt = new Fixture();
        doThrow(new TermException(TermException.Reason.UNAVAILABLE)).when(receipt.commands).save(any(), any());
        assertThatThrownBy(() -> receipt.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"))
                .isInstanceOf(TermException.class);
        assertThat(receipt.events).containsExactly("begin", "entity", "revision", "rollback-request");
        verify(receipt.audit, never()).term(any(), any(), any(), anyLong(), anyLong(), any());
        var audit = new Fixture();
        RuntimeException failure = new IllegalStateException("审计依赖探针");
        doThrow(failure).when(audit.audit).term(any(), any(), any(), anyLong(), anyLong(), any());
        assertThatThrownBy(() -> audit.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable"))
                .isSameAs(failure);
        assertThat(audit.events).containsExactly("begin", "entity", "revision", "receipt", "rollback-request");
    }

    @Test
    void replaysStillRecheckIdentityButDoNotWriteEntityReceiptOrAudit() {
        var f = new Fixture();
        var previous = new TermRows.Command("disable", TermRows.Operation.SEARCH_TERM_ENABLED, 7, TermRows.EntityKind.TERM,
                9L, 3L, 1, "a".repeat(64), "{}", null);
        when(f.commands.lock(any())).thenReturn(previous);
        when(f.commands.receipt(previous)).thenReturn(new TermReceipt(TermRows.EntityKind.TERM, 9, 4, 11, false));
        assertThat(f.service.enabled(9, new TermInputs.EnabledWrite(false, 3), "disable").replayed()).isTrue();
        verify(f.access).recheckWriter(7);
        assertThat(f.events).containsExactly("begin", "commit-request");
        verify(f.mapper, never()).term(anyLong());
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final TermDatabase database = mock(TermDatabase.class);
        private final TermAccess access = mock(TermAccess.class);
        private final TermMapper mapper = mock(TermMapper.class);
        private final TermCommands commands = mock(TermCommands.class);
        private final TermAudit audit = mock(TermAudit.class);
        private final TermWriteService service = new TermWriteService(database, access, mapper, commands, audit);

        private Fixture() {
            when(database.write(any())).thenAnswer(invocation -> {
                events.add("begin");
                try {
                    Object result = invocation.<Supplier<?>>getArgument(0).get();
                    events.add("commit-request");
                    return result;
                } catch (RuntimeException failure) {
                    events.add("rollback-request");
                    throw failure;
                }
            });
            when(access.requireWrite()).thenReturn(7L);
            when(commands.bind(any(), anyString(), anyLong(), any(), any(), any())).thenReturn(
                    new TermCommands.Binding(TermRows.Operation.SEARCH_TERM_ENABLED, "disable", 7, TermRows.EntityKind.TERM, 9L, 3L, "a".repeat(64)));
            when(mapper.lockState()).thenReturn(10L);
            when(mapper.term(9)).thenReturn(new TermRows.Term(9, "名称", "名称", "释义", true, 3, null, null));
            when(mapper.enableTerm(anyLong(), any())).thenAnswer(invocation -> {
                events.add("entity");
                return 1;
            });
            when(mapper.advanceRevision(10)).thenAnswer(invocation -> {
                events.add("revision");
                return 1;
            });
            doAnswer(invocation -> {
                events.add("receipt");
                return null;
            }).when(commands).save(any(), any());
            doAnswer(invocation -> {
                events.add("audit");
                return null;
            }).when(audit).term(any(), any(), any(), anyLong(), anyLong(), any());
        }
    }
}
