package com.zhishiku.kb.app.search;

import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchFilters;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchFiltersTest {
    @Test
    void emptyAndExplicitRestrictionsStayIndependent() {
        assertThat(SearchFilters.empty().restricted()).isFalse();
        assertThat(new SearchFilters(Long.MAX_VALUE, null, false, List.of(), null, null, null).restricted()).isTrue();
        assertThat(new SearchFilters(null, 1L, false, List.of(), null, null, null).restricted()).isTrue();
        assertThat(new SearchFilters(null, null, true, List.of(), null, null, null).restricted()).isTrue();
        for (var type : SearchFilters.FileType.values()) {
            assertThat(new SearchFilters(null, null, false, List.of(), type, null, null).restricted()).isTrue();
        }
        assertThatThrownBy(() -> SearchFilters.FileType.valueOf("UNPUBLISHED")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tagsAreSortedCopiedAndBoundedWithoutLosingLargeIds() {
        var tags = new ArrayList<>(List.of(Long.MAX_VALUE, 2L));
        var filters = new SearchFilters(null, null, false, tags, null, null, null);
        tags.clear();
        assertThat(filters.tagIds()).containsExactly(2L, Long.MAX_VALUE);
        assertThatThrownBy(() -> filters.tagIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new SearchFilters(null, null, false, LongStream.rangeClosed(1, 20).boxed().toList(), null, null, null).tagIds()).hasSize(20);
        assertThatThrownBy(() -> new SearchFilters(null, null, false, LongStream.rangeClosed(1, 21).boxed().toList(), null, null, null)).isInstanceOf(SearchFailure.class);
    }

    @Test
    void invalidIdsAndConflictingCategoryFailAsInput() {
        for (Long id : List.of(0L, -1L)) {
            assertThatThrownBy(() -> new SearchFilters(id, null, false, List.of(), null, null, null)).isInstanceOf(SearchFailure.class);
            assertThatThrownBy(() -> new SearchFilters(null, id, false, List.of(), null, null, null)).isInstanceOf(SearchFailure.class);
        }
        for (List<Long> tags : List.of(List.of(1L, 1L), List.of(0L), Arrays.asList(1L, null))) {
            assertThatThrownBy(() -> new SearchFilters(null, null, false, tags, null, null, null))
                    .isInstanceOfSatisfying(SearchFailure.class, error -> assertThat(error.status()).isEqualTo(400));
        }
        assertThatThrownBy(() -> new SearchFilters(null, 1L, true, List.of(), null, null, null)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> new SearchFilters(null, null, false, null, null, null, null)).isInstanceOf(SearchFailure.class);
    }

    @Test
    void timeBoundsCompareInstantsAndAllowOneSidedRanges() {
        var start = OffsetDateTime.parse("2026-10-01T08:00:00+08:00");
        var same = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        assertThatThrownBy(() -> new SearchFilters(null, null, false, List.of(), null, start, same)).isInstanceOf(SearchFailure.class);
        assertThatThrownBy(() -> new SearchFilters(null, null, false, List.of(), null, start, same.minusSeconds(1))).isInstanceOf(SearchFailure.class);
        assertThat(new SearchFilters(null, null, false, List.of(), null, start, same.plusNanos(1000)).updatedTo()).isEqualTo(same.plusNanos(1000));
        assertThat(new SearchFilters(null, null, false, List.of(), null, start, null).restricted()).isTrue();
        assertThat(new SearchFilters(null, null, false, List.of(), null, null, same).restricted()).isTrue();
    }
}
