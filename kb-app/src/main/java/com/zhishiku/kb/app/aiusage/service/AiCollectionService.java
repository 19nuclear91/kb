package com.zhishiku.kb.app.aiusage.service;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

/**
 * 采集区间和清理操作只访问观测表；实例配置不一致时拒绝清理。
 */
@Service
public class AiCollectionService {
    private final AiUsageMapper mapper;
    private final AiObservationProperties properties;
    private final Clock clock;
    private final AiObservationHeartbeat heartbeat;
    private final String instanceId = UUID.randomUUID().toString();

    @Autowired
    public AiCollectionService(AiUsageMapper mapper, AiObservationProperties properties, AiObservationHeartbeat heartbeat) {
        this(mapper, properties, heartbeat, Clock.systemUTC());
    }

    public AiCollectionService(AiUsageMapper mapper, AiObservationProperties properties, AiObservationHeartbeat heartbeat, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.heartbeat = heartbeat;
        this.clock = clock;
    }

    @Transactional(timeout = 10)
    public void synchronize() {
        heartbeat.refresh(instanceId);
        mapper.registrationLock("ai-collection-period");
        if (!consistent()) throw ApiException.unavailable("AI 观测实例配置不一致");
        mapper.pruneInstances(clock.instant());
        if (properties.isEnabled()) {
            mapper.closeChangedPolicy(clock.instant(), properties.getRetentionDays());
            mapper.openPeriod(clock.instant(), properties.getRetentionDays());
        }
        else mapper.closePeriod(clock.instant());
    }

    public boolean consistent() {
        return mapper.inconsistentInstances(clock.instant(), properties.isEnabled(), properties.getRetentionDays()) == 0;
    }

    @Transactional(timeout = 10)
    public int cleanup() {
        if (!properties.isCleanupEnabled() || !consistent() || !mapper.cleanupLock()) return 0;
        mapper.registrationLock("ai-collection-period");
        if (!consistent()) throw ApiException.unavailable("AI 观测清理策略不一致");
        Instant cutoff = clock.instant().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
        int removed = 0;
        Instant cleared = null;
        for (AiCallRow candidate : mapper.cleanupCandidates(cutoff, properties.getCleanupBatchSize())) {
            AiCallRow current = mapper.lockCall(candidate.getCallId());
            if (!eligible(current, cutoff)) continue;
            // 删除前再次读取有效策略；不一致或动态延长期限时本批失败回滚。
            if (!consistent()) throw ApiException.unavailable("AI 观测清理策略不一致");
            Instant latestCutoff = clock.instant().minus(properties.getRetentionDays(), ChronoUnit.DAYS);
            if (!eligible(current, latestCutoff)) continue;
            Instant boundary = mapper.deletionBoundary(current.getCallId());
            if (boundary == null) throw ApiException.unavailable("AI 观测清理界限不可用");
            mapper.deleteEvents(current.getCallId());
            if (mapper.deleteCall(current.getCallId()) != 1) throw AiUsageWriter.conflict();
            if (cleared == null || boundary.isAfter(cleared)) cleared = boundary;
            removed++;
        }
        if (cleared != null && mapper.advanceCoverage(cleared) != 1)
            throw ApiException.unavailable("AI 观测清理界限未确认");
        return removed;
    }

    public static boolean eligible(AiCallRow row, Instant cutoff) {
        return row != null && !"RUNNING".equals(row.getGenerationStatus())
                && Set.of("SETTLED", "NOT_REQUIRED").contains(row.getSettlementStatus())
                && row.getFinalizedAt() != null && row.getRetentionAnchorAt() != null
                && !row.getRetentionAnchorAt().isAfter(cutoff);
    }
}
