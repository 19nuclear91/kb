package com.zhishiku.kb.app.aiusage.service;

import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.common.aiusage.AiObservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 多轮只在原业务事务末尾投影事实，不自行重放账本或回填旧轮次。
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AiMultiObservation {
    private final AiUsageWriter writer;
    private final AiUsageMapper mapper;
    private final AiObservationProperties properties;
    private final AuthService auth;

    public AiMultiObservation(AiUsageWriter writer, AiUsageMapper mapper, AiObservationProperties properties, AuthService auth) {
        this.writer = writer; this.mapper = mapper; this.properties = properties; this.auth = auth;
    }

    public void begin(long user, TurnRow turn) {
        if (!properties.isEnabled()) return;
        var principal = auth.quotaPrincipal(user);
        writer.registerMulti(new AiObservation.Registration(1, turn.getId(), turn.getCreatedAt(), "MULTI", turn.getId()), user, principal.departmentId());
    }

    public void reserved(SettlementRow settlement) {
        var row = mapper.call(settlement.getTurnId().toString());
        if (row == null) return;
        if (row.getDepartmentId() != settlement.getDepartmentId() || row.getUserId() != settlement.getUserId()) throw AiUsageWriter.conflict();
        if (exists(row.getCallId(), "RESERVED")) return;
        writer.accept(event(row, "RESERVED", settlement.getCreatedAt(), settlement.getLeaseId(), settlement.getReservedTokens(),
                null, null, null, null, null, null, null));
    }

    public void dispatched(TurnRow turn) {
        if (mapper.call(turn.getId().toString()) != null) simple(turn.getId(), "DISPATCHED", turn.getUpdatedAt());
    }

    public void finished(TurnRow turn, SettlementRow settlement) {
        var row = mapper.call(turn.getId().toString());
        if (row == null || exists(row.getCallId(), "FINALIZED")) return;
        boolean rejected = "QUOTA_REJECTED".equals(row.getResultCode());
        String result = rejected ? "QUOTA_REJECTED" : "NO_RESULTS".equals(turn.getAnswerKind()) ? "NO_RESULTS"
                : "COMPLETED".equals(turn.getStatus()) ? "MODEL" : turn.getStatus();
        String state = settlement == null ? (rejected ? "NOT_REQUIRED" : "REVIEW") : "PENDING";
        Long amount = settlement == null ? (rejected ? Long.valueOf(0) : null) : Long.valueOf(settlement.getChargedTokens());
        writer.accept(event(row, "FINALIZED", turn.getFinishedAt(), null, null,
                rejected ? "REJECTED" : turn.getStatus(), result, turn.getUsageStatus(), turn.getPromptTokens(),
                turn.getCompletionTokens(), amount, state));
    }

    public void settled(SettlementRow settlement) {
        var row = mapper.call(settlement.getTurnId().toString());
        if (row == null || !("SETTLED".equals(settlement.getState()) || "REVIEW".equals(settlement.getState()))) return;
        if ("REVIEW".equals(settlement.getState())) { simple(settlement.getTurnId(), "REVIEW", Instant.now()); return; }
        if (exists(row.getCallId(), "SETTLED")) return;
        writer.accept(event(row, "SETTLED", settlement.getSettledAt(), settlement.getLeaseId(), null,
                null, null, null, null, null, settlement.getChargedTokens(), null));
    }

    private void simple(UUID call, String kind, Instant time) {
        var row = mapper.call(call.toString());
        if (row != null && !exists(row.getCallId(), kind))
            writer.accept(event(row, kind, time, null, null, null, null, null, null, null, null, null));
    }

    private boolean exists(String call, String phase) { return mapper.phase(call, phase) != null; }
    private static AiObservation.Event event(AiCallRow row, String kind, Instant time, UUID lease, Long reserved,
                                             String generation, String result, String usage, Long prompt, Long completion,
                                             Long planned, String settlement) {
        UUID call = UUID.fromString(row.getCallId());
        return new AiObservation.Event(1, call, AiObservation.eventId(call, kind), row.getEpoch(), kind, time,
                lease, reserved, generation, result, usage, prompt, completion, planned, settlement);
    }
}
