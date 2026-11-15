package com.zhishiku.kb.app.document.applicability.service;

import com.zhishiku.kb.app.document.applicability.ValidityProperties;
import com.zhishiku.kb.app.document.applicability.mapper.BusinessClockMapper;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 一次 HTTP 请求共享同一个业务日期，包括该请求的列表、计数和响应。 */
@Component
@RequestScope
public class BusinessDate {
    private final BusinessClockMapper clock;
    private final ZoneId zone;
    private LocalDate today;

    public BusinessDate(BusinessClockMapper clock, ValidityProperties properties) {
        this.clock = clock;
        this.zone = properties.zoneId();
    }

    public synchronized LocalDate today() {
        if (today == null) {
            try {
                OffsetDateTime instant = clock.now();
                if (instant == null) {
                    throw new IllegalStateException();
                }
                today = instant.atZoneSameInstant(zone).toLocalDate();
            } catch (RuntimeException ignored) {
                throw ApiException.unavailable("暂时无法确认业务日期");
            }
        }
        return today;
    }
}
