package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.document.applicability.ValidityProperties;
import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthSelection;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.mapper.health.HealthMapper;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HealthReadTest {
    private final HealthAccess access = mock(HealthAccess.class);
    private final BusinessClockMapper clock = mock(BusinessClockMapper.class);
    private final LocalDate today = LocalDate.of(2027, 1, 1);
    private final MemoryFacts facts = new MemoryFacts();
    private final HealthReadServiceImpl service = new HealthReadServiceImpl(access, facts, clock, new ValidityProperties("Asia/Shanghai"));

    @BeforeEach
    void setup() {
        when(access.require(2)).thenReturn(PermissionScope.of(7, (short) 2, List.of(2L), Set.of("1:7")));
        when(clock.now()).thenReturn(OffsetDateTime.parse("2026-12-31T16:30:00Z"));
        facts.rows.add(new HealthRow(1, null, null, null, false, "UNASSIGNED", null, null, null, null, null));
        facts.rows.add(new HealthRow(2, 7L, "负责人", today.minusDays(1), true, "OVERDUE", 20L, null, UntilKind.DATE, today.minusDays(1), "EXPIRED"));
        facts.rows.add(new HealthRow(3, 7L, "负责人", today.plusDays(10), true, "DUE_SOON", null, null, null, null, null));
    }

    @Test
    void unionCountsAndFilteredSparsePageUseOneBusinessDate() {
        var page = service.read(2, new HealthQuery("APPLICABILITY_EXPIRED", 1, null));
        assertThat(page.asOfDate()).isEqualTo(today);
        assertThat(page.affectedDocumentCount()).isEqualTo("3");
        assertThat(page.counts().reviewOverdue()).isEqualTo("1");
        assertThat(page.counts().applicabilityExpired()).isEqualTo("1");
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().documentId()).isEqualTo("2");
        assertThat(page.items().getFirst().signals()).containsExactly(HealthSignal.REVIEW_OVERDUE, HealthSignal.APPLICABILITY_EXPIRED);
        assertThat(page.hasMore()).isFalse();
        verify(clock).now();
        assertThat(facts.selections).allMatch(selection -> selection.today().equals(today));
        assertThat(HealthBudget.current()).isNull();
    }

    @Test
    void cursorRefreshesFactsAndRechecksAccessInsteadOfFreezingOldPage() {
        var first = service.read(2, new HealthQuery("ALL", 1, null));
        assertThat(first.hasMore()).isTrue();
        facts.rows.removeFirst();
        var second = service.read(2, new HealthQuery("ALL", 1, first.nextCursor()));
        assertThat(second.items().getFirst().documentId()).isEqualTo("2");
        assertThat(second.affectedDocumentCount()).isEqualTo("2");
        when(access.require(2)).thenThrow(QueryException.missing());
        assertThatThrownBy(() -> service.read(2, new HealthQuery("ALL", 1, second.nextCursor()))).isInstanceOf(QueryException.class);
        assertThat(HealthBudget.current()).isNull();
    }

    @Test
    void failureNeverBecomesEmptyAndDoesNotReadClockBeforeAuthorization() {
        when(access.require(2)).thenThrow(QueryException.forbidden());
        assertThatThrownBy(() -> service.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(QueryException.class);
        verifyNoInteractions(clock);
        doReturn(PermissionScope.of(7, (short) 2, List.of(), Set.of("1:7"))).when(access).require(2);
        var mapper = mock(HealthMapper.class);
        when(mapper.totals(any())).thenThrow(new IllegalStateException("private database failure"));
        var broken = new HealthReadServiceImpl(access, mapper, clock, new ValidityProperties("Asia/Shanghai"));
        assertThatThrownBy(() -> broken.read(2, new HealthQuery("ALL", 20, null)))
                .isInstanceOf(HealthFailure.class).hasMessage("UNAVAILABLE").hasNoCause();
    }

    @Test
    void emptySuccessAndMismatchedMapperOutputAreDifferent() {
        facts.rows.clear();
        var empty = service.read(2, new HealthQuery("ALL", 20, null));
        assertThat(empty.affectedDocumentCount()).isEqualTo("0");
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        facts.rows.add(new HealthRow(1, null, null, null, false, "OVERDUE", null, null, null, null, null));
        assertThatThrownBy(() -> service.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(HealthFailure.class);
    }

    @Test
    void incompleteFirstPageOrContradictingCategoryCannotBecomeSuccess() {
        var mapper = mock(HealthMapper.class);
        var checked = new HealthReadServiceImpl(access, mapper, clock, new ValidityProperties("Asia/Shanghai"));
        when(mapper.totals(any())).thenReturn(new HealthTotals("1", "0", "0", "0", "0", "0", "1"));
        when(mapper.page(any())).thenReturn(List.of());
        assertThatThrownBy(() -> checked.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(HealthFailure.class);
        when(mapper.page(any())).thenReturn(List.of(facts.rows.get(1)));
        assertThatThrownBy(() -> checked.read(2, new HealthQuery("ALL", 20, null))).isInstanceOf(HealthFailure.class);
    }

    /** 测试只替换存储边界；验证服务的汇总、分页与事实变化，不执行或证明生产SQL。 */
    private static final class MemoryFacts implements HealthMapper {
        private final List<HealthRow> rows = new ArrayList<>();
        private final List<HealthSelection> selections = new ArrayList<>();

        @Override
        public HealthTotals totals(HealthSelection selection) {
            selections.add(selection);
            var counts = new long[6];
            for (var row : rows) {
                for (var signal : HealthProjection.item(row, selection.today()).signals()) {
                    counts[signal.ordinal()]++;
                }
            }
            return new HealthTotals(Long.toString(counts[0]), Long.toString(counts[1]), Long.toString(counts[2]),
                    Long.toString(counts[3]), Long.toString(counts[4]), Long.toString(counts[5]), Integer.toString(rows.size()));
        }

        @Override
        public List<HealthRow> page(HealthSelection selection) {
            selections.add(selection);
            return rows.stream().filter(row -> selection.afterId() == null || row.documentId() > selection.afterId())
                    .filter(row -> "ALL".equals(selection.signal()) || HealthProjection.item(row, selection.today()).signals().contains(HealthSignal.valueOf(selection.signal())))
                    .limit(selection.limit()).toList();
        }
    }
}
