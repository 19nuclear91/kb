package com.zhishiku.kb.app.conversation.service;

import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.aiusage.service.AiMultiObservation;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * 领取与结果写回各自短事务，网络投递由调度器在事务外执行。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationMaintenanceService {
    private final ConversationMapper mapper;
    private final ConversationProperties properties;
    private final Clock clock;
    private AiMultiObservation observation;

    @Autowired
    public void setObservation(AiMultiObservation observation) { this.observation = observation; }

    @Autowired
    public ConversationMaintenanceService(ConversationMapper mapper, ConversationProperties properties) {
        this(mapper, properties, Clock.systemUTC());
    }

    public ConversationMaintenanceService(ConversationMapper mapper, ConversationProperties properties, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Long> expired() {
        return mapper.expired(clock.instant(), properties.getWorkBatchSize());
    }

    @Transactional
    public List<SettlementRow> claim() {
        return mapper.claimSettlements(clock.instant(), clock.instant().plusSeconds(30), properties.getWorkBatchSize());
    }

    public boolean retryable(SettlementRow row) {
        return row.getFinalizedAt() != null && clock.instant().isBefore(row.getFinalizedAt().plusSeconds(properties.getSettlementRetrySeconds()));
    }

    @Transactional
    public void acknowledge(SettlementRow claimed, boolean successful) {
        var row = mapper.settlement(claimed.getTurnId());
        if (row == null || !"PENDING".equals(row.getState()) || row.getAttempts() != claimed.getAttempts()
                || !claimed.getClaimedUntil().equals(row.getClaimedUntil())) return;
        var claim = row.getClaimedUntil();
        row.setClaimedUntil(null);
        if (successful) {
            row.setState("SETTLED");
            row.setSettledAt(clock.instant());
        } else if (!retryable(row)) row.setState("REVIEW");
        else row.setNextAttemptAt(clock.instant().plusSeconds(Math.min(300, 1L << Math.min(8, row.getAttempts()))));
        int changed = mapper.deliveryResult(row.getTurnId(), row.getAttempts(), claim, row.getState(),
                row.getNextAttemptAt(), row.getSettledAt());
        if (changed < 0 || changed > 1) throw ConversationService.conflict();
        if (changed == 1 && observation != null) observation.settled(row);
    }
}

