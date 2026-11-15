package com.zhishiku.kb.app.document.applicability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/** 全站日期口径；上线后不得修改。 */
@ConfigurationProperties("kb.validity")
public record ValidityProperties(@DefaultValue("Asia/Shanghai") String businessTimeZone) {
    public ValidityProperties {
        if (businessTimeZone == null || !ZoneId.getAvailableZoneIds().contains(businessTimeZone)) {
            throw new IllegalArgumentException("kb.validity.business-time-zone 必须是有效地区 ID");
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(businessTimeZone);
    }
}
