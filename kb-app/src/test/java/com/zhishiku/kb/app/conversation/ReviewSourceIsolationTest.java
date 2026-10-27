package com.zhishiku.kb.app.conversation;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.service.ConversationProperties;
import com.zhishiku.kb.app.conversation.service.ConversationService;
import com.zhishiku.kb.app.conversation.service.TurnExecutionService;
import com.zhishiku.kb.app.document.mapper.DocumentMapper;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import com.zhishiku.kb.common.security.PermissionScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 来源仓储替身只放行已发布来源；人工候选权限不能传入模型。 */
class ReviewSourceIsolationTest {
    final PermissionService permissions = mock(PermissionService.class);
    final DocumentMapper mapper = mock(DocumentMapper.class);
    final DocumentVersionAccessService access = new DocumentVersionAccessService(mapper, permissions);
    final SourceKey published = new SourceKey(10, 20), candidate = new SourceKey(10, 21);

    ReviewSourceIsolationTest() {
        when(permissions.resolve(any(PermissionService.PrincipalHandle.class), eq("view")))
                .thenReturn(PermissionScope.of(1, (short) 2, List.of(3L), List.of("1:1")));
        when(mapper.visibleVersions(anyList(), anyList(), anyList(), anyShort(), any()))
                .thenAnswer(call -> {
                    List<SourceKey> requested = call.getArgument(0);
                    var purpose = call.getArgument(4);
                    return requested.stream().filter(key -> purpose == VersionAccessPurpose.INTERACTIVE || key.equals(published)).toList();
                });
    }

    @Test
    void mixedCandidateBlocksWholeHistoryAndContextEvenForInteractiveParticipant() {
        var fixture = new ConversationServiceTest();
        fixture.conversation();
        var sources = Set.of(published, candidate);
        var turn = fixture.turn("MODEL", sources);
        when(fixture.mapper.history(9, 8)).thenReturn(List.of(turn));
        var service = new ConversationService(fixture.mapper, fixture.identity, access, new ConversationProperties());
        assertThat(access.visible(fixture.handle, sources, VersionAccessPurpose.INTERACTIVE)).containsExactlyInAnyOrderElementsOf(sources);
        var blocked = service.turn(1, 9, turn.getId());
        assertThat(blocked.visibility()).isEqualTo("BLOCKED");
        assertThat(blocked.answer()).isNull();
        assertThat(blocked.citations()).isEmpty();
        assertThat(service.prepareContext(1, 9)).isEmpty();
        verify(fixture.mapper, never()).citations(turn.getId());
        verify(mapper, atLeastOnce()).visibleVersions(anyList(), anyList(), anyList(), anyShort(), eq(VersionAccessPurpose.MODEL_SOURCE));
    }

    @Test
    void sourceSealRejectsCandidateBeforePersistingAnySourceOrCitation() {
        var fixture = new TurnExecutionServiceTest();
        var key = fixture.begin();
        var reads = new ConversationService(fixture.mapper, fixture.identity, access, fixture.properties, fixture.clock);
        var writes = new TurnExecutionService(fixture.mapper, reads, fixture.identity, access, fixture.properties,
                new ObjectMapper(), fixture.clock);
        var citation = new CitationView(10, 21, 30, "候选", 2, null, null, null, null, null, null, null, null, false);
        assertThatThrownBy(() -> writes.seal(1, key, "MODEL", List.of(), List.of(citation)))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(403));
        verify(fixture.mapper, never()).insertSource(any());
        verify(fixture.mapper, never()).insertCitation(any());
        assertThat(fixture.turn(key).isSourceSealed()).isFalse();
    }
}
