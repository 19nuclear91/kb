package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.config.KnowledgeSubscriptionProperties;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.domain.subscription.KnowledgeDelivery;
import com.zhishiku.kb.app.enums.subscription.DeliveryState;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeDerivationMapper;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeQueryMapper;
import com.zhishiku.kb.common.review.PublicationOrder;
import com.zhishiku.kb.common.security.PermissionScope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 生产事务组件的独立仓储边界测试，不以替身代替真实数据库锁与回滚证据。 */
class KnowledgeDerivationTest {
    private final KnowledgeDerivationMapper mapper = mock(KnowledgeDerivationMapper.class);
    private final KnowledgeOrderMapper orders = mock(KnowledgeOrderMapper.class);
    private final KnowledgeQueryMapper queries = mock(KnowledgeQueryMapper.class);
    private final SubscriptionAccess access = mock(SubscriptionAccess.class);
    private final KnowledgeSubscriptionProperties properties = new KnowledgeSubscriptionProperties();
    private final KnowledgeDerivationTransaction service = new KnowledgeDerivationTransaction(mapper, orders, queries, access, properties);
    private final UUID event = UUID.randomUUID();
    private final LocalDate week = LocalDate.of(2026, 11, 23);
    private final KnowledgeWork.Claim claim = new KnowledgeWork.Claim(event, null, 5, 0, 0, false);

    @BeforeEach
    void setup() {
        when(mapper.lockMatch(claim, "app")).thenReturn(0L);
        when(mapper.sourceReady(event)).thenReturn(true);
        when(mapper.advanceMatch(any(), eq("app"), anyLong(), anyBoolean(), anyInt())).thenReturn(1);
        when(orders.next()).thenReturn(new PublicationOrder(50, Instant.parse("2026-11-30T01:00:00Z")));
        when(access.current(7)).thenReturn(PermissionScope.of(7, (short) 2, List.of(3L), List.of("1:7")));
    }

    @Test
    void fourOriginalGenerationsCreateOneUpdateAndRetainAllSupportsBeforeAdvancing() {
        var matches = List.of(new KnowledgeWork.Match(7, UUID.randomUUID(), SubscriptionMode.PER_PUBLICATION, week),
                new KnowledgeWork.Match(7, UUID.randomUUID(), SubscriptionMode.PER_PUBLICATION, week),
                new KnowledgeWork.Match(7, UUID.randomUUID(), SubscriptionMode.PER_PUBLICATION, week),
                new KnowledgeWork.Match(7, UUID.randomUUID(), SubscriptionMode.PER_PUBLICATION, week));
        when(mapper.recipients(event, 0, 100)).thenReturn(List.of(7L));
        when(mapper.matches(event, List.of(7L))).thenReturn(matches);
        UUID update = UUID.randomUUID();
        when(mapper.saveUpdate(any())).thenReturn(update);
        assertThat(service.match(claim, "app")).isTrue();
        verify(mapper, times(1)).saveUpdate(any());
        for (var match : matches) {
            verify(mapper).saveMatch(update, 7, match.generationId());
        }
        var order = inOrder(mapper);
        order.verify(mapper).saveUpdate(any());
        order.verify(mapper, times(4)).saveMatch(eq(update), eq(7L), any());
        order.verify(mapper).registerImmediate(update, 7);
        order.verify(mapper).advanceMatch(claim, "app", 7, true, 60);
    }

    @Test
    void lostClaimUnknownSourceAndFailedMemberNeverCompletePartialBatch() {
        when(mapper.lockMatch(claim, "app")).thenReturn(null);
        assertThatThrownBy(() -> service.match(claim, "app")).isInstanceOf(KnowledgeLeaseLost.class);
        verify(mapper, never()).recipients(any(), anyLong(), anyInt());
        when(mapper.lockMatch(claim, "app")).thenReturn(0L);
        when(mapper.sourceReady(event)).thenReturn(false);
        when(mapper.waitSource(claim, "app", 30)).thenReturn(1);
        assertThat(service.match(claim, "app")).isTrue();
        verify(mapper).waitSource(claim, "app", 30);
        when(mapper.sourceReady(event)).thenReturn(true);
        when(mapper.recipients(event, 0, 100)).thenReturn(List.of(7L));
        var generation = UUID.randomUUID();
        when(mapper.matches(event, List.of(7L))).thenReturn(List.of(new KnowledgeWork.Match(7, generation, SubscriptionMode.WEEKLY, week)));
        var update = UUID.randomUUID();
        when(mapper.saveUpdate(any())).thenReturn(update);
        when(mapper.saveMatch(update, 7, generation)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> service.match(claim, "app")).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).advanceMatch(any(), any(), anyLong(), anyBoolean(), anyInt());
        verify(mapper, never()).registerImmediate(any(), anyLong());
    }

    @Test
    void weeklyClosureWaitsForAllPublicationMatchesAndResumesRecipientCursor() {
        var weekly = new KnowledgeWork.Claim(null, week, 2, 0, 0, false);
        when(mapper.lockWeek(weekly, "app")).thenReturn(100L);
        when(mapper.release("weekly", weekly, "app", 30, false)).thenReturn(1);
        assertThat(service.week(weekly, "app")).isTrue();
        verify(mapper, never()).weeklyRecipients(any(), anyLong(), anyInt());
        when(mapper.weekComplete(week)).thenReturn(true);
        when(mapper.weeklyRecipients(week, 100, 100)).thenReturn(List.of(101L, 105L));
        when(mapper.advanceWeek(weekly, "app", 105, true, 60)).thenReturn(1);
        assertThat(service.week(weekly, "app")).isTrue();
        verify(mapper).registerWeekly(week, 101);
        verify(mapper).registerWeekly(week, 105);
        verifyNoInteractions(queries);
    }

    @Test
    void currentReadFailureDoesNotBecomeEmptyAndDeliveryUsesOneOriginalDispatchPosition() {
        var delivery = new KnowledgeDelivery(event, 7, SubscriptionMode.WEEKLY, null, week, DeliveryState.PENDING, null, null);
        when(mapper.lockDelivery(claim, "app")).thenReturn(delivery);
        when(queries.eligible(any())).thenThrow(new IllegalStateException("permission unavailable"));
        assertThatThrownBy(() -> service.deliver(claim, "app")).isInstanceOf(IllegalStateException.class);
        verify(mapper, never()).finishDelivery(any(), any(), anyBoolean(), anyLong(), any());
        doReturn(false).when(queries).eligible(any());
        when(mapper.finishDelivery(eq(claim), eq("app"), anyBoolean(), eq(50L), any())).thenReturn(1);
        service.deliver(claim, "app");
        verify(mapper, never()).notify(any());
        when(queries.eligible(any())).thenReturn(true);
        when(mapper.notify(delivery)).thenReturn(1);
        service.deliver(claim, "app");
        var arguments = ArgumentCaptor.forClass(KnowledgeQuery.class);
        verify(queries, times(3)).eligible(arguments.capture());
        assertThat(arguments.getValue().dispatchPosition()).isEqualTo(50);
        assertThat(arguments.getValue().weekStart()).isEqualTo(week);
        verify(mapper).notify(delivery);
    }

    @Test
    void fixedCalendarIncludesSundayButExcludesNextMondayAndCrossesYears() {
        assertThat(KnowledgeCalendar.week(Instant.parse("2027-01-03T15:59:59.999999Z"))).isEqualTo(LocalDate.of(2026, 12, 28));
        assertThat(KnowledgeCalendar.week(Instant.parse("2027-01-03T16:00:00Z"))).isEqualTo(LocalDate.of(2027, 1, 4));
        assertThat(KnowledgeCalendar.due(LocalDate.of(2026, 12, 28))).isEqualTo(Instant.parse("2027-01-04T01:00:00Z"));
        when(mapper.nextWeek()).thenReturn(week);
        when(mapper.createWeek(week, 50, Instant.parse("2026-11-30T01:00:00Z"))).thenReturn(1);
        assertThat(service.discoverWeek()).isTrue();
        when(orders.next()).thenReturn(new PublicationOrder(51, Instant.parse("2026-11-30T00:59:59.999999Z")));
        assertThat(service.discoverWeek()).isFalse();
    }

    @Test
    void metricsKeepLastCompleteSampleAndScrapesDoNotQueryStorage() {
        var registry = new SimpleMeterRegistry();
        var metrics = new KnowledgeMetrics(mapper, registry);
        when(mapper.backlog()).thenReturn(new KnowledgeWork.Backlog(3, 2, 5, 90, Instant.EPOCH)).thenThrow(new IllegalStateException());
        metrics.sample();
        assertThat(registry.get("kb.knowledge.subscriptions.backlog").tag("stage", "delivery").gauge().value()).isEqualTo(5);
        metrics.sample();
        assertThat(registry.get("kb.knowledge.subscriptions.snapshot.available").gauge().value()).isZero();
        assertThat(registry.get("kb.knowledge.subscriptions.backlog").tag("stage", "match").gauge().value()).isEqualTo(3);
        verify(mapper, times(2)).backlog();
        properties.setLeaseSeconds(29);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        assertThat(properties.backoff(100)).isEqualTo(900);
    }
}
