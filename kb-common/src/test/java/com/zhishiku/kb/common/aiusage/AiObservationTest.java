package com.zhishiku.kb.common.aiusage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiObservationTest {
    @Test
    void unknownAndNotInvokedCannotBeConfusedWithKnownZero() {
        assertThat(AiObservation.usage(true, false, 999L, 999L)).isEqualTo(new AiObservation.Usage("UNKNOWN", null, null));
        assertThat(AiObservation.usage(false, false, null, null)).isEqualTo(new AiObservation.Usage("NOT_INVOKED", 0L, 0L));
        assertThat(AiObservation.usage(true, true, 0L, 0L)).isEqualTo(new AiObservation.Usage("KNOWN", 0L, 0L));
        assertThat(AiObservation.usage(true, true, 10L, 5L).prompt()).isEqualTo(10);
        assertThatThrownBy(() -> AiObservation.usage(true, true, null, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixedEventDigestIsStableAndSensitiveToFacts() {
        UUID id = UUID.randomUUID();
        var event = new AiObservation.Event(1, id, AiObservation.eventId(id, "FINALIZED"), 1,
                "FINALIZED", Instant.EPOCH, null, null, "COMPLETED", "MODEL", "KNOWN", 10L, 5L, 15L, "PENDING");
        assertThat(event.digest()).isEqualTo(event.digest()).hasSize(64);
        assertThat(AiObservation.eventId(id, "FINALIZED")).isEqualTo(event.eventId());
        assertThatThrownBy(() -> new AiObservation.Event(1, id, event.eventId(), 1, "FINALIZED", Instant.EPOCH,
                null, null, "COMPLETED", "MODEL", "UNKNOWN", 0L, 0L, 0L, "PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAndInvalidConfigurationDoNotExposeSecrets() {
        var settings = new AiObservationSettings();
        settings.validate(true);
        assertThat(settings.getRetentionDays()).isEqualTo(180);
        assertThat(settings.isEnabled()).isFalse();
        settings.setEnabled(true);
        assertThatThrownBy(() -> settings.validate(true)).hasMessage("AI 观测服务密钥无效");
        settings.setSecret("p".repeat(40));
        settings.validate(true);
        settings.setRetentionDays(0);
        assertThatThrownBy(() -> settings.validate(true)).hasMessage("AI 观测配置无效");
        settings.setRetentionDays(180); settings.setEnabled(false); settings.setSecret("short");
        assertThatThrownBy(() -> settings.validate(true)).hasMessage("AI 观测服务密钥无效");
        settings.setSecret(" ".repeat(40));
        assertThatThrownBy(() -> settings.validate(true)).hasMessage("AI 观测服务密钥无效");
    }
}
