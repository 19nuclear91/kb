package com.zhishiku.kb.app.domain.subscription;

import com.zhishiku.kb.app.enums.subscription.DeliveryState;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeDeliveryTest {
    @Test
    void separatesWeeklyAndPerPublicationSources() {
        var id = UUID.randomUUID();
        var update = UUID.randomUUID();
        var week = LocalDate.of(2026, 11, 23);
        assertThat(new KnowledgeDelivery(id, 1L, SubscriptionMode.WEEKLY, null, week,
                DeliveryState.PENDING, null, null).weekStart()).isEqualTo(week);
        assertThat(new KnowledgeDelivery(id, 1L, SubscriptionMode.PER_PUBLICATION, update, null,
                DeliveryState.PENDING, null, null).updateId()).isEqualTo(update);
        assertThatThrownBy(() -> new KnowledgeDelivery(id, 1L, SubscriptionMode.WEEKLY, update, week,
                DeliveryState.PENDING, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeDelivery(id, 0L, SubscriptionMode.WEEKLY, null, week,
                DeliveryState.PENDING, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresCompleteOutcomeAndMondayWeek() {
        var id = UUID.randomUUID();
        var week = LocalDate.of(2026, 11, 23);
        assertThatThrownBy(() -> new KnowledgeDelivery(id, 1L, SubscriptionMode.WEEKLY, null, week,
                DeliveryState.NOTIFIED, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KnowledgeDelivery(id, 1L, SubscriptionMode.WEEKLY, null, week.plusDays(1),
                DeliveryState.PENDING, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new KnowledgeDelivery(id, 1L, SubscriptionMode.WEEKLY, null, week,
                DeliveryState.NO_ELIGIBLE, 10L, Instant.now()).status()).isEqualTo(DeliveryState.NO_ELIGIBLE);
    }
}
