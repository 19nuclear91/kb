package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 反连接发现的分支测试；内存替身不证明真实提交顺序。 */
class NotificationDiscoveryTest {
    private final DerivationHarness harness = new DerivationHarness();

    @Test
    void repeatedAndConcurrentDiscoveryRegistersEachEventOnce() {
        var store = harness.store;
        UUID event = store.addRaw("REVIEW_SUBMITTED", 1L, store.now, 2L, 3L);
        var first = harness.instance();
        var second = harness.instance();
        first.runOnce();
        second.runOnce();
        first.runOnce();
        assertThat(store.discover(50)).isZero();
        assertThat(store.delivered(event)).hasSize(2);
        assertThat(store.projection(event).status).isEqualTo("DONE");
    }

    @Test
    void lateCommittedEventWithSmallerPositionIsStillDiscovered() {
        var store = harness.store;
        var service = harness.instance();
        UUID later = store.addRaw("REVIEW_SUBMITTED", 1L, Instant.parse("2026-10-25T00:00:10Z"), 2L);
        service.runOnce();
        assertThat(store.projection(later).status).isEqualTo("DONE");
        // 较早位置的事件在后续才提交：全局高水位会跳过它，反连接仍能发现。
        UUID earlier = store.addRaw("REVIEW_SUBMITTED", 1L, Instant.parse("2026-10-25T00:00:01Z"), 3L);
        service.runOnce();
        assertThat(store.projection(earlier).status).isEqualTo("DONE");
        assertThat(store.delivered(earlier)).extracting(InMemoryNotificationStore.Delivered::recipient).containsExactly(3L);
    }

    @Test
    void discoveryIsBoundedPerRoundAndCarriesNoHighWaterMark() {
        harness.properties.setDiscoverLimit(2);
        harness.properties.setClaimLimit(2);
        var store = harness.store;
        for (int i = 0; i < 5; i++) store.addRaw("REVIEW_SUBMITTED", 1L, store.now.plusSeconds(i), 2L);
        var service = harness.instance();
        assertThat(service.runOnce()).isEqualTo(2);
        assertThat(service.runOnce()).isEqualTo(2);
        assertThat(service.runOnce()).isEqualTo(1);
        assertThat(store.delivered()).hasSize(5);
        Method discover = Arrays.stream(NotificationDerivationMapper.class.getMethods())
                .filter(method -> method.getName().equals("discover")).findFirst().orElseThrow();
        assertThat(discover.getParameterTypes()).containsExactly(int.class);
    }
}
