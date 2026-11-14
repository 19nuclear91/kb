package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.config.UuidTypeHandler;
import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.NotificationBacklog;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationService;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.app.notification.service.NotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.support.MapperRowStub;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 单次双来源汇总、固定指标及失败不假零；仅内存/配置验证，不执行 SQL。 */
class GapNotificationMetricsTest {
    private final NotificationDerivationMapper mapper = mock(NotificationDerivationMapper.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationMetrics metrics = new NotificationMetrics(meters);
    private final NotificationProperties properties = new NotificationProperties();
    private final NotificationDerivationService service = new NotificationDerivationService(mock(NotificationDerivationTransaction.class),
            mapper, DerivationHarness.reviewRegistry(), properties, metrics);

    @Test
    void oneCombinedSnapshotUpdatesExistingGaugeNamesWithoutDuplicateSourceLabels() {
        when(mapper.backlog()).thenReturn(new NotificationBacklog(7, 3, 120));
        service.observe();
        verify(mapper, times(1)).backlog();
        assertThat(pending()).isEqualTo(7);
        assertThat(waiting()).isEqualTo(3);
        assertThat(age()).isEqualTo(120);
        assertThat(meters.find("kb.notification.derivation.backlog").gauges()).hasSize(2);
        Set<String> allowedKeys = Set.of("state", "stage");
        for (var meter : meters.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertThat(tag.getKey()).isIn(allowedKeys);
                assertThat(tag.getValue()).doesNotContain("gap", "user", "event");
            }
        }
    }

    @Test
    void nullFailureAndInvalidCountsKeepLastReliableSnapshotInsteadOfZero() {
        when(mapper.backlog()).thenReturn(new NotificationBacklog(7, 3, 120));
        service.observe();
        doThrow(new IllegalStateException("secret sql")).when(mapper).backlog();
        service.observe();
        doReturn(null).when(mapper).backlog();
        service.observe();
        doReturn(new NotificationBacklog(-1, 0, 0)).when(mapper).backlog();
        service.observe();
        assertThat(pending()).isEqualTo(7);
        assertThat(waiting()).isEqualTo(3);
        assertThat(age()).isEqualTo(120);
        assertThat(meters.get("kb.notification.derivation.failures").tag("stage", "backlog").counter().count()).isEqualTo(3);
    }

    @Test
    void completeSourceCountsUndiscoveredIntentAndExcludesDoneFromBothNamespaces() {
        var legacy = new InMemoryNotificationStore();
        var gap = new InMemoryNotificationStore(GapNotificationDerivationTransaction.SOURCE_KINDS);
        legacy.addRaw("REVIEW_SUBMITTED", 7L, legacy.now.minusSeconds(120), 8L);
        gap.addRaw("GAP_ASSIGNED", 7L, gap.now.minusSeconds(60), 8L);
        assertThat(legacy.backlog().pending()).isEqualTo(1);
        assertThat(gap.backlog().pending()).isEqualTo(1);
        when(mapper.backlog()).thenAnswer(call -> {
            var first = legacy.backlog();
            var second = gap.backlog();
            return new NotificationBacklog(first.pending() + second.pending(), first.waitingSource() + second.waitingSource(),
                    Math.max(first.oldestAgeSeconds(), second.oldestAgeSeconds()));
        });
        service.observe();
        assertThat(pending()).isEqualTo(2);
        assertThat(age()).isEqualTo(120);
        legacy.discover(1);
        var claim = legacy.claim("owner", 1, 60).getFirst();
        legacy.complete(claim.eventId(), "owner", claim.claimToken());
        service.observe();
        assertThat(pending()).isEqualTo(1);
        assertThat(age()).isEqualTo(60);
    }

    @Test
    void bothSourceFailuresShareOnlyExistingStageCountersAndOneDiscoveryTimer() {
        var legacy = mock(NotificationDerivationTransaction.class);
        var gap = mock(GapNotificationDerivationTransaction.class);
        doThrow(new IllegalStateException("secret legacy")).when(legacy).discover();
        doThrow(new IllegalStateException("secret gap")).when(gap).discover();
        when(legacy.claim(anyString())).thenReturn(List.of());
        when(gap.claim(anyString())).thenReturn(List.of());
        new NotificationDerivationService(legacy, mapper, DerivationHarness.reviewRegistry(), properties, metrics).runOnce();
        new GapNotificationDerivationService(gap, properties, metrics).runOnce();
        assertThat(meters.get("kb.notification.derivation.failures").tag("stage", "discover").counter().count()).isEqualTo(2);
        assertThat(meters.get("kb.notification.derivation.discover").timer().count()).isEqualTo(2);
        assertThat(meters.find("kb.notification.derivation.discover").timers()).hasSize(1);
    }

    @Test
    void actualBacklogQueryUsesOneBoundedAggregateOverBothFullSourcesWithoutBody() throws Exception {
        var configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidTypeHandler.class);
        try (var input = getClass().getResourceAsStream("/mapper/NotificationDerivationMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "/mapper/NotificationDerivationMapper.xml", configuration.getSqlFragments()).parse();
        }
        String id = NotificationDerivationMapper.class.getName() + ".backlog";
        var statement = configuration.getMappedStatement(id);
        var bound = statement.getBoundSql(Map.of());
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        assertThat(sql).contains("union all", "from kb_collaboration_event e left join kb_notification_projection",
                "from kb_knowledge_gap_event e left join kb_knowledge_gap_notification_projection",
                "coalesce(p.status, 'pending')", "p.status is distinct from 'done'");
        assertThat(sql).doesNotContain("kb_knowledge_gap_entry", "body", "description", "title");
        assertThat(bound.getParameterMappings()).isEmpty();
        assertThat(statement.getTimeout()).isEqualTo(10);
        assertThat(statement.getResultMaps().getFirst().getConstructorResultMappings()).hasSize(3);
        var result = MapperRowStub.readOne(configuration, id, Map.of(),
                Map.of("pending", 7L, "waiting_source", 3L, "oldest_age_seconds", 120L), NotificationBacklog.class);
        assertThat(result).isEqualTo(new NotificationBacklog(7, 3, 120));
    }

    private double pending() {
        return meters.get("kb.notification.derivation.backlog").tag("state", "pending").gauge().value();
    }

    private double waiting() {
        return meters.get("kb.notification.derivation.backlog").tag("state", "waiting_source").gauge().value();
    }

    private double age() {
        return meters.get("kb.notification.derivation.oldest_age_seconds").gauge().value();
    }
}
