package com.zhishiku.kb.app.service.health;

import com.zhishiku.kb.app.domain.req.health.HealthQuery;
import com.zhishiku.kb.app.domain.vo.health.KnowledgeHealthRadarVo;

/** 当前身份下的单空间只读诊断。 */
public interface HealthReadService {
    KnowledgeHealthRadarVo read(long spaceId, HealthQuery query);
}
