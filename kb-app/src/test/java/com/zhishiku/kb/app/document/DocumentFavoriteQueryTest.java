package com.zhishiku.kb.app.document;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentFavoriteMapper;
import com.zhishiku.kb.app.document.model.DocumentFavoriteRow;
import com.zhishiku.kb.app.document.model.DocumentFavoriteViews;
import com.zhishiku.kb.app.document.service.DocumentFavoriteService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentFavoriteQueryTest {
    @Test
    void losingCandidateParticipationHidesExistingFavoriteWithoutPlaceholderOrCount() {
        when(mapper.list(7, null, 21, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(List.of(row(50)), List.of());
        when(mapper.states(7, List.of(150L), List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(List.of(new DocumentFavoriteViews.State(150, true)), List.of());
        assertThat(service.list(Map.of()).items()).hasSize(1);
        assertThat(service.states(List.of(150L)).items()).hasSize(1);
        var after = service.list(Map.of());
        assertThat(after.items()).isEmpty();
        assertThat(after.hasMore()).isFalse();
        assertThat(after.nextCursor()).isNull();
        assertThat(service.states(List.of(150L)).items()).isEmpty();
        verify(permissions, times(4)).resolve(handle, "view");
    }
    private final AdminQueryAccess access = mock(AdminQueryAccess.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PermissionService.PrincipalHandle handle = mock(PermissionService.PrincipalHandle.class);
    private final DocumentFavoriteMapper mapper = mock(DocumentFavoriteMapper.class);
    private final DocumentFavoriteService service = new DocumentFavoriteService(access, permissions, mapper);

    @BeforeEach
    void setup() {
        when(access.current()).thenReturn(handle);
        when(handle.userId()).thenReturn(7L);
        when(permissions.resolve(handle, "view"))
                .thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
    }

    @Test
    void usesLastReturnedPositionAndKeepsNullableCurrentVersion() {
        when(mapper.list(7, null, 3, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(List.of(row(50), row(49), row(48)));
        var first = service.list(Map.of("limit", List.of("2")));
        assertThat(first.items()).extracting(DocumentFavoriteViews.Item::documentId).containsExactly(150L, 149L);
        assertThat(first.items().getFirst().currentVersionId()).isNull();
        assertThat(first.hasMore()).isTrue();
        var cursor = QueryCursor.decode(first.nextCursor(), "document-favorites");
        assertThat(cursor.id()).isEqualTo(49);
        cursor.verify("document-favorites", QueryParameters.filters("userId", 7));
        when(mapper.list(7, 49L, 3, List.of(3L), List.of("1:7"), (short) 2)).thenReturn(List.of(row(48)));
        var next = service.list(Map.of("limit", List.of("2"), "cursor", List.of(first.nextCursor())));
        assertThat(next.items()).extracting(DocumentFavoriteViews.Item::documentId).containsExactly(148L);
        assertThat(next.hasMore()).isFalse();
        assertThat(next.nextCursor()).isNull();
        verify(access, times(2)).current();
    }

    @Test
    void defaultAndMaximumPageSizesIncludeOnlyOneLookahead() {
        when(mapper.list(anyLong(), any(), anyInt(), anyList(), anyList(), anyShort())).thenReturn(List.of());
        assertThat(service.list(Map.of()).hasMore()).isFalse();
        assertThat(service.list(Map.of("limit", List.of("100"))).items()).isEmpty();
        verify(mapper).list(7, null, 21, List.of(3L), List.of("1:7"), (short) 2);
        verify(mapper).list(7, null, 101, List.of(3L), List.of("1:7"), (short) 2);
    }

    @Test
    void invalidLimitsParametersAndForeignCursorsFailBeforeQuery() {
        for (String limit : List.of("0", "101", "-1", "1.5", " 20")) {
            assertThatThrownBy(() -> service.list(Map.of("limit", List.of(limit)))).isInstanceOf(QueryException.class);
        }
        assertThatThrownBy(() -> service.list(Map.of("limit", List.of("1", "2")))).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> service.list(Map.of("userId", List.of("8")))).isInstanceOf(QueryException.class);
        for (String cursor : List.of("broken", QueryCursor.encode("other", Map.of(), 1),
                QueryCursor.encode("document-favorites", QueryParameters.filters("userId", 8), 1))) {
            assertThatThrownBy(() -> service.list(Map.of("cursor", List.of(cursor)))).isInstanceOf(QueryException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void statesRestoreRequestOrderAndDoNotInventMissingItems() {
        var ids = List.of(4L, 2L, 3L);
        when(mapper.states(7, ids, List.of(3L), List.of("1:7"), (short) 2))
                .thenReturn(List.of(new DocumentFavoriteViews.State(2, false), new DocumentFavoriteViews.State(4, true)));
        assertThat(service.states(ids).items()).containsExactly(
                new DocumentFavoriteViews.State(4, true), new DocumentFavoriteViews.State(2, false));
        verify(access).current();
        verify(permissions).resolve(handle, "view");
        verify(mapper).states(7, ids, List.of(3L), List.of("1:7"), (short) 2);
    }

    @Test
    void invalidBatchIsRejectedRatherThanSilentlyCoerced() {
        assertThatThrownBy(() -> service.states(null)).isInstanceOf(QueryException.class);
        for (var ids : List.of(List.<Long>of(), List.of(1L, 1L), List.of(0L),
                LongStream.rangeClosed(1, 101).boxed().toList())) {
            assertThatThrownBy(() -> service.states(ids)).isInstanceOf(QueryException.class);
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void emptyScopeReturnsNoContentAndNeverTouchesMapper() {
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.denyAll(7));
        assertThat(service.list(Map.of()).items()).isEmpty();
        assertThat(service.states(List.of(1L)).items()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void queryFailureIsNotAnEmptyPageOrFalseState() {
        when(mapper.list(anyLong(), any(), anyInt(), anyList(), anyList(), anyShort()))
                .thenThrow(new IllegalStateException("private sql"));
        when(mapper.states(anyLong(), anyList(), anyList(), anyList(), anyShort()))
                .thenThrow(new IllegalStateException("private sql"));
        assertUnavailable(() -> service.list(Map.of()));
        assertUnavailable(() -> service.states(List.of(1L)));
        when(access.current()).thenThrow(new IllegalStateException("identity unavailable"));
        assertUnavailable(() -> service.list(Map.of()));
    }

    @Test
    void disabledAndPasswordStatesCannotRead() {
        when(access.current()).thenThrow(IdentityException.disabled("停用"), IdentityException.passwordChangeRequired("改密"));
        assertThatThrownBy(() -> service.list(Map.of())).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.states(List.of(1L))).isInstanceOf(IdentityException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void unexpectedOrDuplicateQueryRowsAreUnavailable() {
        when(mapper.states(anyLong(), anyList(), anyList(), anyList(), anyShort()))
                .thenReturn(List.of(new DocumentFavoriteViews.State(2, true)),
                        List.of(new DocumentFavoriteViews.State(1, true), new DocumentFavoriteViews.State(1, false)));
        assertUnavailable(() -> service.states(List.of(1L)));
        assertUnavailable(() -> service.states(List.of(1L)));
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
    }

    private DocumentFavoriteRow row(long id) {
        return new DocumentFavoriteRow(id, id + 100, "标题", 3, (short) 1, null, null, Instant.EPOCH);
    }
}
