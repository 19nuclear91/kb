package com.zhishiku.kb.app.aiusage.service;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 活实例策略声明先独立提交，区间同步失败不能撤销其可见性。
 */
@Service
public class AiObservationHeartbeat {
    private final AiUsageMapper mapper;
    private final AiObservationProperties properties;
    private final Clock clock;

    @Autowired
    public AiObservationHeartbeat(AiUsageMapper mapper, AiObservationProperties properties) {
        this(mapper, properties, Clock.systemUTC());
    }

    public AiObservationHeartbeat(AiUsageMapper mapper, AiObservationProperties properties, Clock clock) {
        this.mapper = mapper; this.properties = properties; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void refresh(String instanceId) {
        if (mapper.heartbeat(instanceId, properties.isEnabled(), properties.getRetentionDays(), clock.instant().plusSeconds(90)) != 1)
            throw ApiException.unavailable("AI 观测实例心跳未确认");
    }
}
