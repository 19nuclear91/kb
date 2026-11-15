package com.zhishiku.kb.app.document.applicability;

import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDateTest {
    @Test
    void configurationRejectsUnknownIdsAndOffsetSpellings() {
        for (String value : List.of("", "Invalid/Zone", "+08:00", "Z", "GMT+08:00", "UTC+08:00", " Asia/Shanghai")) {
            assertThatThrownBy(() -> new ValidityProperties(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ValidityProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ValidityProperties("Asia/Shanghai").zoneId().getId()).isEqualTo("Asia/Shanghai");
        assertThat(new ValidityProperties("Europe/Paris").zoneId().getId()).isEqualTo("Europe/Paris");
    }

    @Test
    void databaseInstantIsConvertedOncePerRequestEvenAcrossMidnight() {
        var calls = new AtomicInteger();
        BusinessClockMapper clock = () -> calls.getAndIncrement() == 0
                ? OffsetDateTime.parse("2026-12-31T16:30:00Z") : OffsetDateTime.parse("2027-01-01T16:30:00Z");
        var properties = new ValidityProperties("Asia/Shanghai");
        var firstRequest = new BusinessDate(clock, properties);
        assertThat(firstRequest.today()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(firstRequest.today()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(calls).hasValue(1);
        assertThat(new BusinessDate(clock, properties).today()).isEqualTo(LocalDate.of(2027, 1, 2));
        assertThat(calls).hasValue(2);
    }

    @Test
    void unavailableClockDoesNotFallBackToHostTimeOrExposeCause() {
        BusinessClockMapper clock = () -> {
            throw new IllegalStateException("private-database-details");
        };
        assertThatThrownBy(() -> new BusinessDate(clock, new ValidityProperties("Asia/Shanghai")).today())
                .isInstanceOf(ApiException.class).hasMessage("暂时无法确认业务日期");
        assertThatThrownBy(() -> new BusinessDate(() -> null, new ValidityProperties("Asia/Shanghai")).today())
                .isInstanceOf(ApiException.class).hasMessage("暂时无法确认业务日期");
    }
}
