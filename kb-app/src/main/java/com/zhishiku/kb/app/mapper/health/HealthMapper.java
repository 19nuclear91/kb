package com.zhishiku.kb.app.mapper.health;

import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthSelection;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

/** 单空间最小关系、六类统计及先鉴权后分页。 */
@Mapper
public interface HealthMapper {
    HealthTotals totals(HealthSelection selection);

    List<HealthRow> page(HealthSelection selection);
}
