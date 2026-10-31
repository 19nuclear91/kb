package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.SourceRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.SourceDeclaration;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {
    final ConversationMapper mapper = mock(ConversationMapper.class);
    final ConversationIdentityGate identity = mock(ConversationIdentityGate.class);
    final DocumentVersionAccessService documents = mock(DocumentVersionAccessService.class);
    final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    final ConversationService service = new ConversationService(mapper, identity, documents,
            new ConversationProperties());

    ConversationServiceTest() {
        when(identity.require(1)).thenReturn(handle);
    }

    ConversationRow conversation() {
        var row = new ConversationRow();
        row.setId(9L);
        row.setOwnerUserId(1);
        row.setTitle("新会话");
        row.setCreationDigest(RequestSignature.digest("新会话"));
        row.setCreatedAt(Instant.EPOCH);
        row.setUpdatedAt(Instant.EPOCH);
        when(mapper.find(9)).thenReturn(row);
        return row;
    }

    TurnRow turn(String kind, Set<SourceKey> keys) {
        var row = new TurnRow();
        row.setId(UUID.randomUUID());
        row.setConversationId(9);
        row.setTurnSeq(1);
        row.setStatus("COMPLETED");
        row.setQuestion("本人问题");
        row.setAssistantText("机密回答");
        row.setAnswerKind(kind);
        row.setSourceSealed(true);
        row.setExpectedSourceCount(keys.size());
        row.setSourceDigest(SourceDeclaration.digest(keys));
        when(mapper.turn(row.getId())).thenReturn(row);
        when(mapper.citations(row.getId())).thenReturn(List.of());
        when(mapper.sources(row.getId())).thenReturn(keys.stream().map(key -> {
            var source = new SourceRow();
            source.setTurnId(row.getId());
            source.setDocumentId(key.documentId());
            source.setVersionId(key.versionId());
            return source;
        }).toList());
        return row;
    }

    @Test
    void failedTurnNeverEntersHistoryEvenWithAnOtherwiseValidSourceDeclaration() {
        conversation();
        when(documents.visible(any(), any())).thenAnswer(call -> call.getArgument(1));
        var failed = turn("MODEL", Set.of(new SourceKey(1, 2)));
        failed.setStatus("FAILED");
        when(mapper.history(eq(9L), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(failed));
        assertThat(service.prepareContext(1, 9)).isEmpty();
        assertThat(service.prepare(1, 9)).isEmpty();
    }

    @Test
    void createsDefaultTitleAndNormalizesReplayBinding() {
        when(mapper.insertConversation(any())).thenAnswer(call -> {
            ((ConversationRow) call.getArgument(0)).setId(9L);
            return 1;
        });
        assertThat(service.create(1, null, " request ", null).replayed()).isFalse();
        var row = conversation();
        when(mapper.findCreation(1, "request")).thenReturn(row);
        assertThat(service.create(1, " 新会话 ", "request", "request").replayed()).isTrue();
        assertStatus(() -> service.create(1, "其他", "request", null), 409);
        row.setDeletedAt(Instant.EPOCH);
        assertStatus(() -> service.create(1, null, "request", null), 404);
        assertStatus(() -> service.create(1, " ", "new", null), 400);
        assertStatus(() -> service.create(1, null, "a", "b"), 400);
        assertStatus(() -> service.create(1, null, "😀".repeat(129), null), 400);
    }

    @Test
    void restrictsStableOwnerAndReadsCurrentIdentityEveryRequest() {
        var row = conversation();
        service.detail(1, 9);
        service.detail(1, 9);
        verify(identity, times(2)).require(1);
        assertStatus(() -> service.detail(2, 9), 404);
        when(identity.require(1)).thenThrow(IdentityException.disabled("disabled"));
        assertThatThrownBy(() -> service.detail(1, 9)).isInstanceOf(IdentityException.class);
        row.setDeletedAt(Instant.EPOCH);
        doReturn(handle).when(identity).require(1);
        assertStatus(() -> service.detail(1, 9), 404);
    }

    @Test
    void createdOrderCursorAndLimitsExcludePreviews() {
        var first = conversation();
        var second = conversation();
        second.setId(8L);
        when(mapper.list(eq(1L), isNull(), isNull(), eq(2))).thenReturn(List.of(first, second));
        var page = service.list(1, 1, null);
        assertThat(page.nextCursor()).isNotNull();
        when(mapper.list(1, Instant.EPOCH, 9L, 2)).thenReturn(List.of(second));
        assertThat(service.list(1, 1, page.nextCursor()).items()).hasSize(1);
        when(mapper.list(eq(1L), isNull(), isNull(), eq(21))).thenReturn(List.of());
        service.list(1, null, null);
        verify(mapper).list(1, null, null, 21);
        when(mapper.list(eq(1L), isNull(), isNull(), eq(101))).thenReturn(List.of());
        service.list(1, 100, null);
        assertStatus(() -> service.list(1, 101, null), 400);
        assertStatus(() -> service.list(1, 20, "invalid"), 400);
    }

    @Test
    void blocksWholeTransitiveAnswerThenRestoresPermission() {
        conversation();
        Set<SourceKey> keys = Set.of(new SourceKey(10, 11), new SourceKey(20, 21));
        var row = turn("MODEL", keys);
        when(documents.visible(handle, keys)).thenReturn(Set.of(new SourceKey(20, 21)), keys);
        var blocked = service.turn(1, 9, row.getId());
        assertThat(blocked.answer()).isNull();
        assertThat(blocked.citations()).isEmpty();
        assertThat(blocked.visibility()).isEqualTo("BLOCKED");
        assertThat(blocked.question()).isEqualTo("本人问题");
        verify(mapper, never()).citations(row.getId());
        assertThat(service.turn(1, 9, row.getId()).answer()).isEqualTo("机密回答");
    }

    @Test
    void malformedMissingAndEmptyModelDeclarationsFailClosed() {
        conversation();
        when(documents.visible(any(), any())).thenAnswer(call -> call.getArgument(1));
        var row = turn("MODEL", Set.of());
        assertThat(service.turn(1, 9, row.getId()).answer()).isNull();
        row = turn("MODEL", Set.of(new SourceKey(1, 2)));
        row.setExpectedSourceCount(2);
        assertThat(service.turn(1, 9, row.getId()).answer()).isNull();
        row.setExpectedSourceCount(1);
        row.setSourceDigest("corrupt");
        assertThat(service.turn(1, 9, row.getId()).answer()).isNull();
        var invalid = new SourceRow();
        invalid.setTurnId(row.getId());
        invalid.setDocumentId(1);
        invalid.setVersionId(-1);
        when(mapper.sources(row.getId())).thenReturn(List.of(invalid));
        assertThat(service.turn(1, 9, row.getId()).answer()).isNull();
    }

    @Test
    void noResultsAndPartialAreSafeButIncompleteNeverBecomesContext() {
        conversation();
        when(documents.visible(any(), any())).thenAnswer(call -> call.getArgument(1));
        var safe = turn("NO_RESULTS", Set.of());
        safe.setAssistantText(ConversationService.NO_RESULTS);
        assertThat(service.turn(1, 9, safe.getId()).visibility()).isEqualTo("VISIBLE");
        var partial = turn("MODEL", Set.of(new SourceKey(1, 2)));
        partial.setStatus("CANCELLED");
        assertThat(service.turn(1, 9, partial.getId()).answer()).isEqualTo("机密回答");
        when(mapper.history(9, 8)).thenReturn(List.of(partial, safe));
        assertThat(service.prepareContext(1, 9)).hasSize(1);
        when(documents.visible(any(), any())).thenThrow(ApiException.unavailable("safe"));
        assertStatus(() -> service.turn(1, 9, partial.getId()), 503);
    }

    @Test
    void everyIncompleteOrBlockedPairIsExcludedFromPreparedContext() {
        conversation();
        var keys = Set.of(new SourceKey(1, 2));
        var row = turn("MODEL", keys);
        when(documents.visible(handle, keys)).thenReturn(keys);
        when(mapper.history(9, 8)).thenReturn(List.of(row));
        for (String status : List.of("RUNNING", "FAILED", "CANCELLED", "INTERRUPTED")) {
            row.setStatus(status);
            assertThat(service.prepareContext(1, 9)).isEmpty();
        }
        row.setStatus("COMPLETED");
        when(documents.visible(handle, keys)).thenReturn(Set.of());
        assertThat(service.prepareContext(1, 9)).isEmpty();
        when(documents.visible(handle, keys)).thenReturn(keys);
        assertThat(service.prepareContext(1, 9)).hasSize(1);
    }

    static void assertStatus(Runnable call, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(status));
    }
}
