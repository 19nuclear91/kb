package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.document.applicability.ValidityProperties;
import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityDeclaration;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityState;
import com.zhishiku.kb.app.document.applicability.model.UntilKind;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipState;
import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthSelection;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.mapper.health.HealthMapper;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 原始内存事实到服务响应的组件验证；不执行生产SQL，不证明数据库隔离。 */
class HealthFactsTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Test
    void rawScopeExcludesArchivedDeletedMovedAndPreservesUnpublishedUnassigned() {
        var facts = new Facts();
        facts.documents.add(new Document(1, 2, "active", (short) 1, null, null, null));
        facts.documents.add(new Document(2, 2, "archived", (short) 1, null, null, null));
        facts.documents.add(new Document(3, 2, "deleted", (short) 1, null, null, null));
        facts.documents.add(new Document(4, 3, "active", (short) 1, null, null, null));
        var report = service(facts, Set.of()).read(2, new HealthQuery("ALL", 20, null));
        assertThat(report.affectedDocumentCount()).isEqualTo("1");
        assertThat(report.items()).extracting(item -> item.documentId()).containsExactly("1");
        assertThat(report.items().getFirst().applicability()).isNull();
    }

    @Test
    void currentPublishedReadableVersionIsRequiredAndAclMultiplicityDoesNotMultiplyDocuments() {
        var facts = new Facts();
        facts.documents.add(new Document(1, 2, "active", (short) 1, 7L, TODAY.plusMonths(6), 11L));
        facts.documents.add(new Document(2, 2, "active", (short) 3, 7L, TODAY.plusMonths(6), 12L));
        facts.documents.add(new Document(3, 2, "active", (short) 1, 7L, TODAY.plusMonths(6), 13L));
        facts.documents.add(new Document(4, 2, "active", (short) 1, 7L, TODAY.plusMonths(6), 14L));
        facts.documents.add(new Document(5, 2, "active", (short) 1, 7L, TODAY.plusMonths(6), 15L));
        facts.versions.add(new Version(10, 1, true, false, TODAY.minusDays(1)));
        facts.versions.add(new Version(11, 1, true, false, null));
        facts.versions.add(new Version(12, 2, true, false, TODAY.minusDays(1)));
        facts.versions.add(new Version(13, 3, false, false, TODAY.minusDays(1)));
        facts.versions.add(new Version(14, 4, false, true, TODAY.minusDays(1)));
        facts.versions.add(new Version(15, 5, true, false, TODAY.minusDays(1)));
        facts.readableDocuments.addAll(List.of(1L, 2L, 3L, 4L, 4L));
        var report = service(facts, Set.of(7L)).read(2, new HealthQuery("APPLICABILITY_EXPIRED", 1, null));
        assertThat(report.affectedDocumentCount()).isEqualTo("1");
        assertThat(report.counts().applicabilityExpired()).isEqualTo("1");
        assertThat(report.items()).extracting(item -> item.documentId()).containsExactly("4");
        assertThat(report.items().getFirst().applicability().versionId()).isEqualTo("14");
        assertThat(report.hasMore()).isFalse();
    }

    @Test
    void confirmationDoesNotRemoveExpiryAndNewVersionDoesNotInheritOldDates() {
        var facts = new Facts();
        facts.documents.add(new Document(1, 2, "active", (short) 1, 7L, TODAY.minusDays(1), 10L));
        facts.versions.add(new Version(10, 1, true, false, TODAY.minusDays(1)));
        facts.readableDocuments.add(1L);
        var service = service(facts, Set.of(7L));
        assertThat(service.read(2, new HealthQuery("ALL", 20, null)).items().getFirst().signals()).hasSize(2);
        facts.documents.set(0, new Document(1, 2, "active", (short) 1, 7L, TODAY.plusMonths(3), 10L));
        var confirmed = service.read(2, new HealthQuery("ALL", 20, null));
        assertThat(confirmed.counts().reviewOverdue()).isEqualTo("0");
        assertThat(confirmed.counts().applicabilityExpired()).isEqualTo("1");
        facts.versions.add(new Version(11, 1, true, false, null));
        facts.documents.set(0, new Document(1, 2, "active", (short) 1, 7L, TODAY.plusMonths(3), 11L));
        assertThat(service.read(2, new HealthQuery("ALL", 20, null)).affectedDocumentCount()).isEqualTo("0");
    }

    private HealthReadServiceImpl service(Facts facts, Set<Long> eligible) {
        facts.eligible = eligible;
        var access = mock(HealthAccess.class);
        var clock = mock(BusinessClockMapper.class);
        when(access.require(2)).thenReturn(PermissionScope.of(7, (short) 2, List.of(), Set.of("1:7")));
        when(clock.now()).thenReturn(OffsetDateTime.parse("2026-11-27T08:00:00Z"));
        return new HealthReadServiceImpl(access, facts, clock, new ValidityProperties("Asia/Shanghai"));
    }

    private record Document(
            long id,
            long space,
            String status,
            short clearance,
            Long responsible,
            LocalDate due,
            Long current
    ) {
    }

    private record Version(
            long id,
            long document,
            boolean published,
            boolean legacy,
            LocalDate until
    ) {
    }

    private static final class Facts implements HealthMapper {
        private final List<Document> documents = new ArrayList<>();
        private final List<Version> versions = new ArrayList<>();
        private final List<Long> readableDocuments = new ArrayList<>();
        private Set<Long> eligible = Set.of();

        private List<HealthRow> rows(HealthSelection query) {
            var result = new ArrayList<HealthRow>();
            for (var document : documents) {
                if (document.space() != query.spaceId() || Set.of("deleted", "archived").contains(document.status())) {
                    continue;
                }
                boolean qualified = document.responsible() != null && eligible.contains(document.responsible());
                var state = StewardshipState.of(document.responsible(), qualified, document.due(), query.today());
                var version = versions.stream().filter(value -> document.current() != null && value.id() == document.current()
                        && value.document() == document.id() && (value.published() || value.legacy())
                        && document.clearance() <= query.clearance() && readableDocuments.contains(document.id())).findFirst().orElse(null);
                UntilKind until = version == null ? null : version.until() == null ? UntilKind.UNDECLARED : UntilKind.DATE;
                var applicable = version == null ? null : ApplicabilityState.of(new ApplicabilityDeclaration(null, until, version.until()), query.today());
                if (state == StewardshipState.OK && applicable != ApplicabilityState.EXPIRED && applicable != ApplicabilityState.EXPIRING_SOON) {
                    continue;
                }
                result.add(new HealthRow(document.id(), document.responsible(), document.responsible() == null ? null : "负责人",
                        document.due(), qualified, state.name(), version == null ? null : version.id(), null, until,
                        version == null ? null : version.until(), applicable == null ? null : applicable.name()));
            }
            return result;
        }

        @Override
        public HealthTotals totals(HealthSelection query) {
            var rows = rows(query);
            var values = new long[6];
            for (var row : rows) {
                for (var signal : HealthProjection.item(row, query.today()).signals()) {
                    values[signal.ordinal()]++;
                }
            }
            return new HealthTotals("" + values[0], "" + values[1], "" + values[2], "" + values[3], "" + values[4], "" + values[5], "" + rows.size());
        }

        @Override
        public List<HealthRow> page(HealthSelection query) {
            return rows(query).stream().filter(row -> query.afterId() == null || row.documentId() > query.afterId())
                    .filter(row -> query.signal().equals("ALL") || HealthProjection.item(row, query.today()).signals().stream().anyMatch(signal -> signal.name().equals(query.signal())))
                    .sorted(Comparator.comparingLong(HealthRow::documentId)).limit(query.limit()).toList();
        }
    }
}
