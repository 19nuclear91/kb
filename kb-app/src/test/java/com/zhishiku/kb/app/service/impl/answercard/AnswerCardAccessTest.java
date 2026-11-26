package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardSourceMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnswerCardAccessTest {
    private final PermissionMapper identities = mock(PermissionMapper.class);
    private final RolePermissionMapper roles = mock(RolePermissionMapper.class);
    private final PermissionService permissions = new PermissionService(identities, null, roles);
    private final SpaceQueryService spaces = mock(SpaceQueryService.class);
    private final AnswerCardSourceMapper sources = mock(AnswerCardSourceMapper.class);
    private final AnswerCardAccess access = new AnswerCardAccess(permissions, spaces, sources, mock(SqlSessionTemplate.class));

    @BeforeEach
    void setup() {
        when(identities.findClearanceLevel(1)).thenReturn((short) 2);
        when(identities.findRoleIds(1)).thenReturn(List.of());
        when(identities.findDeptChainIds(1)).thenReturn(List.of(2L));
        when(identities.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of(9L));
        when(identities.findVisibleSpaceIds(anyList(), eq("edit"))).thenReturn(List.of(9L));
        when(identities.findVisibleSpaceIds(anyList(), eq("review"))).thenReturn(List.of(9L));
        when(roles.findUserPermissions(1)).thenReturn(List.of(Permission.ANSWER_CARD_WRITE.code()));
    }

    @Test
    void writeAndReviewAreIndependentAndRevocationAppliesToNextRequest() {
        access.requireWriter(access.current(1), 9);
        assertThatThrownBy(() -> access.requireReviewer(access.current(1), 9)).isInstanceOfSatisfying(AnswerCardFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(403));
        when(roles.findUserPermissions(1)).thenReturn(List.of("ROLE_ADMIN"));
        assertThatThrownBy(() -> access.requireWriter(access.current(1), 9)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void documentExceptionNeverGrantsCardSpaceAndManagerDoesNotReadSources() {
        assertThatThrownBy(() -> access.requireWriter(access.current(1), 10)).isInstanceOfSatisfying(AnswerCardFailure.class,
                failure -> assertThat(failure.status()).isEqualTo(404));
        SpaceRecord space = new SpaceRecord();
        space.setId(9L);
        space.setArchived(false);
        when(spaces.findManaged(any(), eq(9L))).thenReturn(space);
        assertThat(access.requireManager(access.current(1), 9, true)).isSameAs(space);
        verify(sources, never()).facts(anyList(), anyList(), anyList(), anyShort());
    }

    @Test
    void allSourcesAreRequiredAndStaleSourceIsDifferentFromUnreadable() {
        var key = new AnswerCardSourceKey(2, 3, 0);
        when(sources.facts(anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of());
        assertThatThrownBy(() -> access.requireSources(access.current(1), List.of(key), false))
                .isInstanceOfSatisfying(AnswerCardFailure.class, error -> assertThat(error.status()).isEqualTo(404));
        var fact = new AnswerCardSourceFact(2, 3, 9, "制度", 1, 4L, 1, null, "UNDECLARED", null);
        when(sources.facts(anyList(), anyList(), anyList(), anyShort())).thenReturn(List.of(fact));
        assertThat(access.requireSources(access.current(1), List.of(key), false)).containsExactly(fact);
        assertThatThrownBy(() -> access.requireSources(access.current(1), List.of(key), true))
                .isInstanceOfSatisfying(AnswerCardFailure.class, error -> assertThat(error.reason()).isEqualTo("SOURCE_CHANGED"));
    }

    @Test
    void permissionsAreAUnionButNeitherImpliesTheOtherOrSpaceAccess() {
        when(roles.findUserPermissions(1)).thenReturn(List.of("answer:card:write", "answer:card:review"));
        access.requireWriter(access.current(1), 9);
        access.requireReviewer(access.current(1), 9);
        when(roles.findUserPermissions(1)).thenReturn(List.of("answer:card:review"));
        access.requireReviewer(access.current(1), 9);
        assertThatThrownBy(() -> access.requireWriter(access.current(1), 9)).isInstanceOf(AnswerCardFailure.class);
        when(identities.findVisibleSpaceIds(anyList(), eq("view"))).thenReturn(List.of());
        assertThatThrownBy(() -> access.requireReviewer(access.current(1), 9)).isInstanceOfSatisfying(AnswerCardFailure.class,
                error -> assertThat(error.status()).isEqualTo(404));
    }

    @Test
    void crossSpaceSourcesUseCurrentClearanceAndOneMissingFactHidesWholeRevision() {
        var first = new AnswerCardSourceKey(2, 3, 0);
        var second = new AnswerCardSourceKey(4, 5, 0);
        var one = new AnswerCardSourceFact(2, 3, 10, "制度一", 1, 3L, 0, null, "UNDECLARED", null);
        var two = new AnswerCardSourceFact(4, 5, 11, "制度二", 1, 5L, 0, null, "UNDECLARED", null);
        when(sources.facts(anyList(), anyList(), anyList(), eq((short) 2))).thenReturn(List.of(one, two), List.of(one));
        assertThat(access.requireSources(access.current(1), List.of(first, second), true)).hasSize(2);
        assertThatThrownBy(() -> access.requireSources(access.current(1), List.of(first, second), true)).isInstanceOf(AnswerCardFailure.class);
        when(identities.findClearanceLevel(1)).thenReturn((short) 1);
        when(sources.facts(anyList(), anyList(), anyList(), eq((short) 1))).thenReturn(List.of());
        assertThatThrownBy(() -> access.requireSources(access.current(1), List.of(first), false)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void archivedSpaceIsAvailableOnlyForMinimalManagementRead() {
        var space = new SpaceRecord();
        space.setArchived(true);
        when(spaces.findManaged(any(), eq(9L))).thenReturn(space);
        assertThat(access.requireManager(access.current(1), 9, false)).isSameAs(space);
        assertThatThrownBy(() -> access.requireManager(access.current(1), 9, true)).isInstanceOfSatisfying(AnswerCardFailure.class,
                error -> assertThat(error.reason()).isEqualTo("SPACE_ARCHIVED"));
    }
}
