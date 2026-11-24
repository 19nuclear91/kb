package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TermAuditTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IdentityAuditService persistent = mock(IdentityAuditService.class);
    private final TermAudit audit = new TermAudit(persistent, json);

    @Test
    void termProducerOnlyAppendsApprovedIdsFlagsVersionsAndFieldNames() throws Exception {
        var binding = new TermCommands.Binding(TermRows.Operation.SEARCH_TERM_UPDATE, "request", 7, TermRows.EntityKind.TERM, 9L, 3L, "private-digest");
        var before = new TermRows.Term(9, "private-before", "private-key", "private-description", true, 3, null, null);
        var after = new TermRows.Term(9, "private-after", "private-key", "private-description", false, 4, null, null);
        audit.term(binding, before, after, 10, 11, List.of("canonicalName", "enabled"));
        var oldState = ArgumentCaptor.forClass(String.class);
        var newState = ArgumentCaptor.forClass(String.class);
        verify(persistent).appendSuccess(eq(AuditScope.SEARCH_TERM_UPDATE), eq("request"), eq(7L), eq(9L), eq("update"), oldState.capture(), newState.capture());
        JsonNode old = json.readTree(oldState.getValue());
        JsonNode current = json.readTree(newState.getValue());
        assertThat(current.properties()).extracting(entry -> entry.getKey())
                .containsExactlyInAnyOrder("termId", "enabled", "version", "dictionaryRevision", "changedFields");
        assertThat(old.get("enabled").booleanValue()).isTrue();
        assertThat(old.get("version").longValue()).isEqualTo(3);
        assertThat(current.get("enabled").booleanValue()).isFalse();
        assertThat(current.get("version").longValue()).isEqualTo(4);
        assertThat(current.get("dictionaryRevision").longValue()).isEqualTo(11);
        assertThat(oldState.getValue() + newState.getValue()).doesNotContain("private-", "query", "selectionContext", "receipt");
    }

    @Test
    void correctionProducerRetainsStableMappingAndBeforeAfterTargetsWithoutExpressions() throws Exception {
        var binding = new TermCommands.Binding(TermRows.Operation.SEARCH_CORRECTION_UPDATE, "request", 7, TermRows.EntityKind.CORRECTION, 9L, 3L, "private-digest");
        var before = new TermRows.Correction(9, "private-before", "private-key", 5, true, 3, null, null);
        var after = new TermRows.Correction(9, "private-after", "private-key", Long.MAX_VALUE, true, 4, null, null);
        audit.correction(binding, before, after, 10, 11, List.of("wrongExpression", "targetTermId"));
        var oldState = ArgumentCaptor.forClass(String.class);
        var newState = ArgumentCaptor.forClass(String.class);
        verify(persistent).appendSuccess(eq(AuditScope.SEARCH_CORRECTION_UPDATE), eq("request"), eq(7L), eq(9L), eq("update"), oldState.capture(), newState.capture());
        assertThat(json.readTree(oldState.getValue()).get("targetTermId").longValue()).isEqualTo(5);
        assertThat(json.readTree(newState.getValue()).get("targetTermId").longValue()).isEqualTo(Long.MAX_VALUE);
        assertThat(oldState.getValue() + newState.getValue()).doesNotContain("private-", "description", "query", "selectionContext");
    }

    @Test
    void nonWhitelistedFieldNamesCannotBecomeStoredTextAndAppendFailurePropagates() {
        var binding = new TermCommands.Binding(TermRows.Operation.SEARCH_TERM_CREATE, "request", 7, TermRows.EntityKind.TERM, null, null, "digest");
        var after = new TermRows.Term(9, "name", "name", "description", true, 0, null, null);
        for (List<String> fields : List.of(List.of("private-query"), List.of("wrongExpression"), List.of("enabled", "enabled"), List.<String>of())) {
            assertThatThrownBy(() -> audit.term(binding, null, after, 0, 1, fields)).isInstanceOf(TermException.class);
        }
        verifyNoInteractions(persistent);
        RuntimeException unavailable = new IllegalStateException("private-audit-failure");
        doThrow(unavailable).when(persistent).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> audit.term(binding, null, after, 0, 1, List.of("canonicalName"))).isSameAs(unavailable);
    }
}
