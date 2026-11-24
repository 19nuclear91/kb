package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermLookupTest {
    private final TermDatabase database = mock(TermDatabase.class);
    private final TermAccess access = mock(TermAccess.class);
    private final TermMapper mapper = mock(TermMapper.class);
    private final TermLookup lookup = new TermLookup(database, access, mapper);

    @Test
    void missingReadOrWriteQualificationDoesNotQueryTargetReceiptOrConflictFacts() {
        inlineRead();
        when(access.requireRead()).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        when(access.requireWrite()).thenThrow(new TermException(TermException.Reason.FORBIDDEN));
        assertThatThrownBy(() -> lookup.term(1)).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> lookup.term(Long.MAX_VALUE)).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> lookup.correction(1)).isInstanceOf(TermException.class);
        assertThatThrownBy(() -> lookup.ownCommand("request")).isInstanceOf(TermException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void everyLookupChecksItsCurrentIndependentQualificationFirst() {
        inlineRead();
        when(access.requireRead()).thenReturn(7L);
        when(access.requireWrite()).thenReturn(7L);
        var term = new TermRows.Term(1, "名称", "名称", "释义", true, 0, null, null);
        when(mapper.term(1)).thenReturn(term);
        assertThat(lookup.term(1)).isSameAs(term);
        assertThatThrownBy(() -> lookup.correction(2)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> lookup.ownCommand("missing")).isInstanceOf(TermException.class);
        var order = inOrder(access, mapper);
        order.verify(access).requireRead();
        order.verify(mapper).term(1);
        order.verify(access).requireRead();
        order.verify(mapper).correction(2);
        order.verify(access).requireWrite();
        order.verify(mapper).command("missing");
    }

    @Test
    void receiptBelongsOnlyToCurrentWriterAndNotToOtherOperators() {
        inlineRead();
        var row = new TermRows.Command("request", TermRows.Operation.SEARCH_TERM_CREATE, 7,
                TermRows.EntityKind.TERM, null, null, 1, "digest", "{}", null);
        when(mapper.command("request")).thenReturn(row);
        when(access.requireWrite()).thenReturn(8L);
        assertThatThrownBy(() -> lookup.ownCommand("request")).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.NOT_FOUND));
        when(access.requireWrite()).thenReturn(7L);
        assertThat(lookup.ownCommand("request")).isSameAs(row);
    }

    private void inlineRead() {
        when(database.read(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
    }
}
