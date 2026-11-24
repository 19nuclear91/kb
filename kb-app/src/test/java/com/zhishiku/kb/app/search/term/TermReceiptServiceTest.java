package com.zhishiku.kb.app.search.term;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermReceiptServiceTest {
    private final TermLookup lookup = mock(TermLookup.class);
    private final TermCommands commands = mock(TermCommands.class);
    private final TermReceiptService service = new TermReceiptService(lookup, commands);

    @Test
    void responseLossCanBeConfirmedOnlyByOwnedFirstReceiptNotByCurrentEntity() {
        var row = new TermRows.Command("request", TermRows.Operation.SEARCH_TERM_UPDATE, 7, TermRows.EntityKind.TERM,
                Long.MAX_VALUE, 3L, 1, "a".repeat(64), "{}", null);
        var first = new TermReceipt(TermRows.EntityKind.TERM, Long.MAX_VALUE, 4, 10, false);
        when(lookup.ownCommand("request")).thenReturn(row);
        when(commands.receipt(row)).thenReturn(first);
        assertThat(service.query(" request ")).isSameAs(first);
        var order = inOrder(lookup, commands);
        order.verify(lookup).ownCommand("request");
        order.verify(commands).receipt(row);
        verify(lookup, never()).term(Long.MAX_VALUE);
        verify(commands, never()).lock(any());
        verify(commands, never()).save(any(), any());
    }

    @Test
    void notFoundOrRevocationNeverFabricatesFailureReceiptOrRetriesTheWrite() {
        for (TermException.Reason reason : new TermException.Reason[]{TermException.Reason.NOT_FOUND, TermException.Reason.FORBIDDEN}) {
            var currentLookup = mock(TermLookup.class);
            var currentCommands = mock(TermCommands.class);
            when(currentLookup.ownCommand("request")).thenThrow(new TermException(reason));
            assertThatThrownBy(() -> new TermReceiptService(currentLookup, currentCommands).query("request"))
                    .isInstanceOfSatisfying(TermException.class, error -> assertThat(error.reason()).isEqualTo(reason));
            verifyNoInteractions(currentCommands);
        }
    }
}
