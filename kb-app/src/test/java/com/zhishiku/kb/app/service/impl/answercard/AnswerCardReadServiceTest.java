package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.answercard.AnswerCardRevision;
import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardReview;
import com.zhishiku.kb.app.enums.answercard.AnswerCardReviewState;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardPageInput;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardQueryMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardSourceMapper;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerCardReadServiceTest {
    private final AnswerCardMapper mapper = mock(AnswerCardMapper.class);
    private final AnswerCardQueryMapper queries = mock(AnswerCardQueryMapper.class);
    private final AnswerCardSourceMapper sources = mock(AnswerCardSourceMapper.class);
    private final PermissionMapper identities = mock(PermissionMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final SpaceQueryService spaces = mock(SpaceQueryService.class);
    private final AnswerCardAccess access = new AnswerCardAccess(new PermissionService(identities, null, roles), spaces, sources, mock(SqlSessionTemplate.class));
    private final AnswerCardReadServiceImpl service = new AnswerCardReadServiceImpl(mapper, queries, sources, access, new ObjectMapper());
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-26T16:00:00Z");

    @BeforeEach
    void setup() {
        when(mapper.now()).thenReturn(now);
        when(identities.findClearanceLevel(anyLong())).thenReturn((short) 2);
        when(identities.findRoleIds(anyLong())).thenReturn(List.of());
        when(identities.findDeptChainIds(anyLong())).thenReturn(List.of());
        when(identities.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(9L));
        when(roles.findUserPermissions(anyLong())).thenReturn(List.of("answer:card:write"));
        when(mapper.card(1)).thenReturn(new AnswerCard(1, 9, 2, 3L, null, now));
        when(mapper.sources(3)).thenReturn(List.of(new AnswerCardSource(3, 4, 5, 0, null, "UNDECLARED", null)));
        when(sources.facts(anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of(fact()));
        when(mapper.revision(3)).thenReturn(new AnswerCardRevision(3, 1, 2, 1, "审定问题", "[]", "😀".repeat(401), "范围",
                null, "UNDECLARED", null, "[]", "审定问题", 7, now));
    }

    @Test
    void searchKeepsMapperPriorityDeduplicatesAndPreservesUnicodePrefix() {
        when(queries.search(any())).thenReturn(List.of(1L, 1L));
        var result = service.search(7, " 问题 ", 9L, 10);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().answerExcerpt()).isEqualTo("😀".repeat(400));
        assertThat(result.items().getFirst().truncated()).isTrue();
        assertThat(result.asOfDate().toString()).isEqualTo("2026-11-27");
        assertThat(result.appliedQuery()).isEqualTo("问题");
    }

    @Test
    void oldOrExpiredFixedDetailNeverFallsBackToAnotherRevision() {
        when(queries.displayable(any())).thenReturn(false);
        assertThatThrownBy(() -> service.detail(7, 1, 3)).isInstanceOfSatisfying(AnswerCardFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(404));
        verify(mapper, never()).revision(anyLong());
        when(queries.displayable(any())).thenReturn(true);
        when(mapper.card(1)).thenReturn(new AnswerCard(1, 9, 3, 6L, null, now));
        assertThatThrownBy(() -> service.detail(7, 1, 3)).isInstanceOf(AnswerCardFailure.class);
        verify(mapper, never()).revision(6);
    }

    @Test
    void missingAnySourceStopsWholeAnswerBeforeReadingItsText() {
        when(queries.displayable(any())).thenReturn(true);
        when(sources.facts(anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of());
        assertThatThrownBy(() -> service.detail(7, 1, 3)).isInstanceOf(AnswerCardFailure.class);
        verify(mapper, never()).revision(anyLong());
    }

    @Test
    void minimalManagerDoesNotReadRevisionSourcesOrPrivateReview() {
        var space = new SpaceRecord();
        space.setArchived(true);
        when(spaces.findManaged(any(), eq(9L))).thenReturn(space);
        var value = service.management(7, 1);
        assertThat(value.spaceArchived()).isTrue();
        assertThat(value.workflowState()).isEqualTo("PUBLISHED");
        verify(mapper, never()).revision(anyLong());
        verify(mapper, never()).sources(anyLong());
        verify(mapper, never()).activeReview(anyLong());
    }

    @Test
    void sourceCursorCannotMoveAcrossActorSpaceOrNameAndOldVersionsAreNotOptions() {
        when(identities.findVisibleSpaceIds(anyList(), eq("edit"))).thenReturn(List.of(9L, 10L));
        when(identities.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(9L, 10L));
        when(sources.candidates(anyString(), any(), any(int.class), anyList(), anyList(), anyShort())).thenReturn(List.of(fact(), fact()));
        var first = service.sources(7, new AnswerCardPageInput(9L, null, null, "制度", 1, null));
        assertThat(first.hasMore()).isTrue();
        for (var input : List.of(new AnswerCardPageInput(10L, null, null, "制度", 1, first.nextCursor()),
                new AnswerCardPageInput(9L, null, null, "其他", 1, first.nextCursor()))) {
            assertThatThrownBy(() -> service.sources(7, input)).isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> service.sources(8, new AnswerCardPageInput(9L, null, null, "制度", 1, first.nextCursor())))
                .isInstanceOf(QueryException.class);
        when(sources.candidates(anyString(), any(), any(int.class), anyList(), anyList(), anyShort()))
                .thenReturn(List.of(new AnswerCardSourceFact(4, 5, 9, "制度", 1, 6L, 0, null, "UNDECLARED", null)));
        assertThatThrownBy(() -> service.sources(7, new AnswerCardPageInput(9L, null, null, "", 1, null)))
                .isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void dependencyFailureIsNotAnEmptySearch() {
        when(queries.search(any())).thenReturn(List.of());
        assertThat(service.search(7, "问题", null, 10).items()).isEmpty();
        when(queries.search(any())).thenReturn(null);
        assertThatThrownBy(() -> service.search(7, "问题", null, 10)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void eachHistoryPageUsesCurrentPerRevisionSourcesAndNoHiddenCount() {
        when(identities.findVisibleSpaceIds(anyList(), eq("edit"))).thenReturn(List.of(9L));
        when(queries.history(any())).thenReturn(List.of(3L, 4L));
        var input = new AnswerCardPageInput(null, "revisions", null, null, 1, null);
        var first = service.history(7, 1, input);
        assertThat(first.items()).hasSize(1);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items().getFirst().revision().revisionId()).isEqualTo("3");
        when(queries.history(any())).thenReturn(List.of());
        var second = service.history(7, 1, new AnswerCardPageInput(null, "revisions", null, null, 1, first.nextCursor()));
        assertThat(second.items()).isEmpty();
        assertThat(second.hasMore()).isFalse();
        assertThatThrownBy(() -> service.history(8, 1, new AnswerCardPageInput(null, "revisions", null, null, 1, first.nextCursor())))
                .isInstanceOf(QueryException.class);
        when(queries.history(any())).thenReturn(List.of(3L));
        when(sources.facts(anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of());
        assertThatThrownBy(() -> service.history(7, 1, input)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void pausedHistoricalContentIsReadableOnlyToCurrentOperators() {
        when(identities.findVisibleSpaceIds(anyList(), eq("edit"))).thenReturn(List.of(9L));
        when(queries.history(any())).thenReturn(List.of(3L));
        when(sources.facts(anyList(), anyList(), anyList(), anyShort()))
                .thenReturn(List.of(new AnswerCardSourceFact(4, 5, 9, "原依据", 1, 6L, 1, null, "UNDECLARED", null)));
        var page = service.history(7, 1, new AnswerCardPageInput(null, "revisions", null, null, 20, null));
        assertThat(page.items().getFirst().revision().availability()).isEqualTo("SOURCE_CHANGED");
        when(roles.findUserPermissions(anyLong())).thenReturn(List.of());
        assertThatThrownBy(() -> service.history(7, 1, new AnswerCardPageInput(null, "revisions", null, null, 20, null)))
                .isInstanceOfSatisfying(AnswerCardFailure.class, value -> assertThat(value.status()).isEqualTo(403));
    }

    @Test
    void synthesizedTierOrderIsRetainedWhileDuplicateCardIdsAreRemoved() {
        for (long id : List.of(2L, 3L)) {
            long revisionId = id + 10;
            when(mapper.card(id)).thenReturn(new AnswerCard(id, 9, 1, revisionId, null, now));
            when(mapper.revision(revisionId)).thenReturn(new AnswerCardRevision(revisionId, id, 20, 1, "问题" + id, "[]", "答案", "范围",
                    null, "UNDECLARED", null, "[]", "问题" + id, 7, now));
            when(mapper.sources(revisionId)).thenReturn(List.of(new AnswerCardSource(revisionId, 4, 5, 0, null, "UNDECLARED", null)));
        }
        // 替身已经按精确问题、问题文本、答案文本的数据库合同排列，服务不能再按ID改排。
        when(queries.search(any())).thenReturn(List.of(3L, 1L, 3L, 2L));
        assertThat(service.search(7, "问题", null, 10).items()).extracting(value -> value.cardId()).containsExactly("3", "1", "2");
    }

    @Test
    void oldApprovedRevisionRetainsHistoricalMeaningInsteadOfBecomingADraft() {
        when(identities.findVisibleSpaceIds(anyList(), eq("edit"))).thenReturn(List.of(9L));
        when(mapper.card(1)).thenReturn(new AnswerCard(1, 9, 3, 6L, null, now));
        when(queries.history(any())).thenReturn(List.of(3L));
        when(mapper.latestReview(3)).thenReturn(new AnswerCardReview(8, 1, 2, 3, 1, AnswerCardReviewState.APPROVED,
                1, 7, now, 9L, now, null));
        var result = service.history(7, 1, new AnswerCardPageInput(null, "revisions", null, null, 20, null));
        assertThat(result.items().getFirst().revision().availability()).isEqualTo("HISTORICAL");
        assertThat(result.items().getFirst().review().state()).isEqualTo("APPROVED");
    }

    private AnswerCardSourceFact fact() {
        return new AnswerCardSourceFact(4, 5, 9, "制度", 1, 5L, 0, null, "UNDECLARED", null);
    }
}
