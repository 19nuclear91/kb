package com.zhishiku.kb.app.domain.dto.health;

import java.time.LocalDate;
import java.util.List;

/** 同次查询的授权范围、日期和分页位置。 */
public record HealthSelection(
        long spaceId,
        LocalDate today,
        List<Long> spaceIds,
        List<String> principalKeys,
        short clearance,
        String signal,
        Long afterId,
        int limit
) {
}
