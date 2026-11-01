package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.mapper.SearchOptionMapper;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchOptions;
import com.zhishiku.kb.app.search.service.SearchOptionReader;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SearchOptionReaderTest {
    private final SearchOptionMapper mapper = mock(SearchOptionMapper.class);
    private final SearchOptionReader reader = new SearchOptionReader(mapper);
    private final PermissionScope scope = PermissionScope.of(7, (short) 2, List.of(1L), List.of("1:7"));

    @Test
    void noPermissionProducesOnlyEmptyProjectionsWithoutQueries() {
        var denied = PermissionScope.denyAll(7);
        assertThat(reader.categories(7, new SearchOptions.CategoryQuery(99, null, 20, null), denied))
                .isEqualTo(new SearchOptions.CategoryPage(List.of(), false, null, 0));
        assertThat(reader.tags(7, new SearchOptions.TagQuery(null, 20, null), denied).items()).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void categoryProjectionBatchesPathsAndOnlyReturnsTheSelectedPage() {
        var page = firstCategoryPage();
        assertThat(page.treeVersion()).isEqualTo(3);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).containsExactly(new SearchOptions.Category(4, 2L, "报销",
                List.of(new SearchOptions.PathNode(2, "制度"), new SearchOptions.PathNode(4, "报销")), 0));
        verify(mapper).paths(99, List.of(4L));
        when(mapper.categories(eq(99L), eq(2L), eq(0), eq(4L), eq(2), anyList(), anyList(), eq((short) 2)))
                .thenReturn(List.of());
        assertThat(reader.categories(7, new SearchOptions.CategoryQuery(99, 2L, 1, page.nextCursor()), scope).items()).isEmpty();
        verify(mapper).categories(99, 2L, 0, 4L, 2, List.of(1L), List.of("1:7"), (short) 2);
    }

    @Test
    void categoryCursorBindsUserSpaceParentAndPurpose() {
        var token = firstCategoryPage().nextCursor();
        for (var query : List.of(new SearchOptions.CategoryQuery(1, 2L, 1, token),
                new SearchOptions.CategoryQuery(99, null, 1, token))) {
            assertThatThrownBy(() -> reader.categories(7, query, scope)).isInstanceOf(SearchFailure.class);
        }
        assertThatThrownBy(() -> reader.categories(8, new SearchOptions.CategoryQuery(99, 2L, 1, token), scope)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> reader.tags(7, new SearchOptions.TagQuery(null, 1, token), scope)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> reader.categories(7, new SearchOptions.CategoryQuery(99, 2L, 1, "e30"), scope)).isInstanceOf(SearchFailure.class);
    }

    @Test
    void hiddenContextDoesNotRevealTreeChangeButVisibleContextReportsConflict() {
        var token = firstCategoryPage().nextCursor();
        when(mapper.treeVersion(99)).thenReturn(4L);
        var query = new SearchOptions.CategoryQuery(99, 2L, 1, token);
        assertThatThrownBy(() -> reader.categories(7, query, scope))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.httpStatus()).isEqualTo(409));
        when(mapper.categoryContext(99, 2L, List.of(1L), List.of("1:7"), (short) 2)).thenReturn(false);
        clearInvocations(mapper);
        assertThat(reader.categories(7, query, scope)).isEqualTo(new SearchOptions.CategoryPage(List.of(), false, null, 0));
        verify(mapper, never()).treeVersion(anyLong());
        verify(mapper, never()).categories(anyLong(), any(), any(), any(), anyInt(), anyList(), anyList(), eq((short) 2));
    }

    @Test
    void malformedPathIsDependencyFailureNotPartialDirectory() {
        firstCategoryPage();
        when(mapper.paths(99, List.of(4L))).thenReturn(List.of(new SearchOptions.PathRow(4, 4, 2L, "报销", 1)));
        assertThatThrownBy(() -> reader.categories(7, new SearchOptions.CategoryQuery(99, 2L, 1, null), scope))
                .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.status()).isEqualTo(503));
    }

    @Test
    void globalTagsKeepDisabledRelationsAndEscapeLiteralNames() {
        var disabled = new SearchOptions.Tag(Long.MAX_VALUE - 1, "a%_\\", false);
        when(mapper.tags(eq("%a\\%\\_\\\\%"), isNull(), eq(2), anyList(), anyList(), eq((short) 2)))
                .thenReturn(List.of(disabled, new SearchOptions.Tag(Long.MAX_VALUE, "other", true)));
        var page = reader.tags(7, new SearchOptions.TagQuery(" a%_\\ ", 1, null), scope);
        assertThat(page.items()).containsExactly(disabled);
        assertThat(page.hasMore()).isTrue();
        reader.tags(7, new SearchOptions.TagQuery("a%_\\", 1, page.nextCursor()), scope);
        verify(mapper).tags("%a\\%\\_\\\\%", Long.MAX_VALUE - 1, 2, List.of(1L), List.of("1:7"), (short) 2);
        assertThatThrownBy(() -> reader.tags(7, new SearchOptions.TagQuery("other", 1, page.nextCursor()), scope)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> reader.tags(8, new SearchOptions.TagQuery("a%_\\", 1, page.nextCursor()), scope)).isInstanceOf(SearchFailure.class);
        clearInvocations(mapper);
        assertThat(reader.tags(7, new SearchOptions.TagQuery("a%_\\", 1, page.nextCursor()), PermissionScope.denyAll(7)).items()).isEmpty();
        verifyNoInteractions(mapper);
    }

    private SearchOptions.CategoryPage firstCategoryPage() {
        when(mapper.categoryContext(99, 2L, List.of(1L), List.of("1:7"), (short) 2)).thenReturn(true);
        when(mapper.treeVersion(99)).thenReturn(3L);
        when(mapper.categories(eq(99L), eq(2L), isNull(), isNull(), eq(2), anyList(), anyList(), eq((short) 2)))
                .thenReturn(List.of(new SearchOptions.CategoryRow(4, 2L, "报销", 0), new SearchOptions.CategoryRow(5, 2L, "费用", 0)));
        when(mapper.paths(99, List.of(4L))).thenReturn(List.of(new SearchOptions.PathRow(4, 2, null, "制度", 2),
                new SearchOptions.PathRow(4, 4, 2L, "报销", 1)));
        return reader.categories(7, new SearchOptions.CategoryQuery(99, 2L, 1, null), scope);
    }
}
