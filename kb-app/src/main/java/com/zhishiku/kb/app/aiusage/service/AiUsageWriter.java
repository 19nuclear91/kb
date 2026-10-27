package com.zhishiku.kb.app.aiusage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.AiObservationProperties;
import com.zhishiku.kb.app.aiusage.mapper.AiUsageMapper;
import com.zhishiku.kb.app.aiusage.model.AiCallRow;
import com.zhishiku.kb.app.aiusage.model.AiEventRow;
import com.zhishiku.kb.common.aiusage.AiObservation;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 仅在短事务中保存元数据；调用行锁先于事件发布锁，无网络调用。
 */
@Service
public class AiUsageWriter {
    private final AiUsageMapper mapper;
    private final AiObservationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public AiUsageWriter(AiUsageMapper mapper, AiObservationProperties properties, ObjectMapper json) {
        this(mapper, properties, json, Clock.systemUTC());
    }

    public AiUsageWriter(AiUsageMapper mapper, AiObservationProperties properties, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(timeout = 10)
    public AiCallRow register(AiObservation.Registration input, long user, long department) {
        return register(input, user, department, false);
    }

    @Transactional(timeout = 10)
    public AiCallRow registerMulti(AiObservation.Registration input, long user, long department) {
        if (!"MULTI".equals(input.mode())) throw invalid();
        return register(input, user, department, true);
    }

    private AiCallRow register(AiObservation.Registration input, long user, long department, boolean acquisitionIntended) {
        if (user <= 0 || department <= 0) throw invalid();
        String id = input.callId().toString();
        mapper.registrationLock(id);
        String digest = RequestSignature.digest(input.version() + "|" + id + "|" + input.initiatedAt()
                + "|" + input.mode() + "|" + input.turnId() + "|" + user + "|" + department);
        AiCallRow old = mapper.lockCall(id);
        if (old != null) {
            if (!digest.equals(old.getRegistrationDigest())) throw conflict();
            return old;
        }
        if (!properties.isEnabled()) throw ApiException.unavailable("AI 观测采集尚未启用");
        Instant now = clock.instant();
        if (input.initiatedAt().isBefore(now.minusSeconds(properties.getRegistrationWindowSeconds()))
                || input.initiatedAt().isAfter(now.plusSeconds(properties.getSignatureWindowSeconds()))) throw invalid();
        var row = new AiCallRow();
        row.setCallId(id);
        row.setRegistrationDigest(digest);
        row.setMode(input.mode());
        row.setTurnId(input.turnId() == null ? null : input.turnId().toString());
        row.setUserId(user);
        row.setDepartmentId(department);
        row.setProtocolVersion(input.version());
        row.setStartedAt(now);
        row.setInitiatedAt(input.initiatedAt());
        row.setEpoch(1);
        row.setGenerationStatus("RUNNING");
        row.setQuotaIntended(acquisitionIntended);
        row.setSettlementStatus(acquisitionIntended ? "PENDING" : "NOT_REQUIRED");
        row.setSettlementPolicy("SINGLE".equals(row.getMode()) ? "SINGLE_EXISTING_V1" : "MULTI_EXISTING_V1");
        row.setLeaseUntil(now.plusSeconds(properties.getLeaseSeconds()));
        changed(mapper.insertCall(row));
        append(row, AiObservation.eventId(input.callId(), "REGISTERED"), "REGISTERED", now, digest);
        if (acquisitionIntended) {
            var intent = new AiObservation.Event(1, input.callId(), AiObservation.eventId(input.callId(), "ACQUIRE_INTENDED"),
                    row.getEpoch(), "ACQUIRE_INTENDED", input.initiatedAt(), null, null, null, null, null, null, null, null, null);
            append(row, intent.eventId(), intent.kind(), intent.occurredAt(), intent.digest());
        }
        return row;
    }

    @Transactional(timeout = 10)
    public AiCallRow accept(AiObservation.Event event) {
        var row = mapper.lockCall(event.callId().toString());
        if (row == null) throw ApiException.notFound("AI 调用未登记或已清理");
        if (!AiObservation.eventId(event.callId(), event.kind()).equals(event.eventId())) throw conflict();
        var old = mapper.phase(row.getCallId(), event.kind());
        if (old != null) {
            if (!event.eventId().toString().equals(old.getEventId()) || !event.digest().equals(old.getPayloadDigest())) throw conflict();
            return row;
        }
        if (row.getEpoch() != event.epoch() || event.occurredAt().isAfter(clock.instant().plusSeconds(properties.getSignatureWindowSeconds()))
                || event.occurredAt().isBefore(row.getInitiatedAt().minusSeconds(properties.getSignatureWindowSeconds()))) throw conflict();
        if ("REGISTERED".equals(event.kind())) throw invalid();
        switch (event.kind()) {
            case "ACQUIRE_INTENDED" -> {
                running(row);
                noFacts(event);
                row.setQuotaIntended(true);
                row.setSettlementStatus("PENDING");
            }
            case "RESERVED" -> {
                running(row);
                if (!row.isQuotaIntended() || event.leaseId() == null || event.reservedTokens() == null
                        || event.reservedTokens() <= 0 || event.generationStatus() != null || event.usageStatus() != null
                        || event.plannedChargedTokens() != null || event.settlementStatus() != null || event.resultCode() != null
                        || event.promptTokens() != null || event.completionTokens() != null) throw invalid();
                row.setLeaseId(event.leaseId().toString());
                row.setReservedTokens(event.reservedTokens());
                row.setSettlementStatus("RESERVED");
            }
            case "DISPATCHED" -> {
                running(row);
                noFacts(event);
                if (!"RESERVED".equals(row.getSettlementStatus())) throw conflict();
                row.setModelDispatchIntent(true);
                row.setUsageStatus("UNKNOWN");
            }
            case "QUOTA_REJECTED" -> {
                running(row);
                noFacts(event);
                if (row.getLeaseId() != null || row.isModelDispatchIntent()) throw conflict();
                row.setResultCode("QUOTA_REJECTED");
                row.setSettlementStatus("NOT_REQUIRED");
                row.setPlannedChargedTokens(0L);
            }
            case "FINALIZED" -> finalizeCall(row, event);
            case "SETTLED" -> {
                if ("RUNNING".equals(row.getGenerationStatus()) || !Set.of("PENDING", "REVIEW").contains(row.getSettlementStatus())
                        || row.getPlannedChargedTokens() == null || !row.getPlannedChargedTokens().equals(event.plannedChargedTokens())
                        || event.leaseId() == null || !event.leaseId().toString().equals(row.getLeaseId())) throw conflict();
                settlementFactsOnly(event);
                row.setSettlementStatus("SETTLED");
                row.setSettledAt(clock.instant());
            }
            case "REVIEW" -> {
                if ("SETTLED".equals(row.getSettlementStatus()) || !row.isQuotaIntended()) throw conflict();
                noFacts(event);
                row.setSettlementStatus("REVIEW");
            }
            default -> throw invalid();
        }
        anchor(row);
        changed(mapper.updateCall(row));
        row.setVersion(row.getVersion() + 1);
        append(row, event.eventId(), event.kind(), event.occurredAt(), event.digest());
        return row;
    }

    private void finalizeCall(AiCallRow row, AiObservation.Event e) {
        running(row);
        if (e.generationStatus() == null || "RUNNING".equals(e.generationStatus()) || e.resultCode() == null
                || e.usageStatus() == null || (e.plannedChargedTokens() == null && !"REVIEW".equals(e.settlementStatus())) || e.leaseId() != null || e.reservedTokens() != null
                || e.settlementStatus() == null || !Set.of("NOT_REQUIRED", "PENDING", "REVIEW").contains(e.settlementStatus())) throw invalid();
        if (row.isModelDispatchIntent() == "NOT_INVOKED".equals(e.usageStatus())) throw invalid();
        if ("NOT_REQUIRED".equals(e.settlementStatus()) && (row.getLeaseId() != null || e.plannedChargedTokens() != 0)) throw invalid();
        if ("PENDING".equals(e.settlementStatus()) && row.getLeaseId() == null) throw invalid();
        row.setGenerationStatus(e.generationStatus());
        row.setResultCode(e.resultCode());
        row.setUsageStatus(e.usageStatus());
        row.setPromptTokens(e.promptTokens());
        row.setCompletionTokens(e.completionTokens());
        row.setPlannedChargedTokens(e.plannedChargedTokens());
        row.setSettlementStatus("REVIEW".equals(row.getSettlementStatus()) && "PENDING".equals(e.settlementStatus()) ? "REVIEW" : e.settlementStatus());
        row.setFinalizedAt(clock.instant());
        row.setLeaseUntil(null);
        if ("NOT_REQUIRED".equals(row.getSettlementStatus())) row.setSettledAt(clock.instant());
    }

    private static void noFacts(AiObservation.Event e) {
        if (e.leaseId() != null || e.reservedTokens() != null || e.generationStatus() != null || e.resultCode() != null
                || e.usageStatus() != null || e.promptTokens() != null || e.completionTokens() != null
                || e.plannedChargedTokens() != null || e.settlementStatus() != null) throw invalid();
    }

    private static void settlementFactsOnly(AiObservation.Event e) {
        if (e.reservedTokens() != null || e.generationStatus() != null || e.resultCode() != null || e.usageStatus() != null
                || e.promptTokens() != null || e.completionTokens() != null || e.settlementStatus() != null) throw invalid();
    }

    private void anchor(AiCallRow row) {
        if (!"RUNNING".equals(row.getGenerationStatus()) && Set.of("SETTLED", "NOT_REQUIRED").contains(row.getSettlementStatus())) {
            Instant settled = row.getSettledAt();
            if (settled == null) throw conflict();
            row.setRetentionAnchorAt(settled.isAfter(row.getFinalizedAt()) ? settled : row.getFinalizedAt());
        } else row.setRetentionAnchorAt(null);
    }

    @Transactional(timeout = 10)
    public void renew(UUID callId, long epoch) {
        var row = mapper.lockCall(callId.toString());
        if (row == null || !"SINGLE".equals(row.getMode()) || row.getEpoch() != epoch) throw conflict();
        running(row);
        if (row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(clock.instant())) throw conflict();
        row.setLeaseUntil(clock.instant().plusSeconds(properties.getLeaseSeconds()));
        changed(mapper.updateCall(row));
    }

    @Transactional(timeout = 10)
    public void recover(String id) {
        var row = mapper.lockCall(id);
        if (row == null || !"SINGLE".equals(row.getMode())) return;
        if (!"RUNNING".equals(row.getGenerationStatus())) {
            if ("PENDING".equals(row.getSettlementStatus()) && row.getFinalizedAt() != null
                    && !row.getFinalizedAt().isAfter(clock.instant().minusSeconds(properties.getLeaseSeconds()))) {
                UUID call = UUID.fromString(id);
                accept(new AiObservation.Event(1, call, AiObservation.eventId(call, "REVIEW"), row.getEpoch(), "REVIEW", clock.instant(),
                        null, null, null, null, null, null, null, null, null));
            }
            return;
        }
        if (row.getLeaseUntil() == null || row.getLeaseUntil().isAfter(clock.instant())) return;
        var usage = AiObservation.usage(row.isModelDispatchIntent(), false, null, null);
        row.setGenerationStatus("INTERRUPTED");
        row.setResultCode("INTERRUPTED");
        row.setUsageStatus(usage.status());
        row.setPromptTokens(usage.prompt());
        row.setCompletionTokens(usage.completion());
        row.setFinalizedAt(clock.instant());
        row.setLeaseUntil(null);
        row.setSettlementStatus(row.isQuotaIntended() ? "REVIEW" : "NOT_REQUIRED");
        if (!row.isQuotaIntended()) {
            row.setPlannedChargedTokens(0L);
            row.setSettledAt(clock.instant());
        }
        anchor(row);
        changed(mapper.updateCall(row));
        String digest = RequestSignature.digest("recovery|" + id + "|" + row.getFinalizedAt());
        append(row, AiObservation.eventId(UUID.fromString(id), "FINALIZED"), "FINALIZED", clock.instant(), digest);
    }

    private void append(AiCallRow row, UUID id, String kind, Instant occurredAt, String digest) {
        var event = new AiEventRow();
        event.setEventId(id.toString());
        event.setCallId(row.getCallId());
        event.setKind(kind);
        event.setPayloadDigest(digest);
        event.setOccurredAt(occurredAt);
        try {
            event.setPayload(json.writeValueAsString(publicView(row)));
        } catch (JsonProcessingException error) { throw ApiException.unavailable("AI 元数据无法保存"); }
        mapper.publicationLock();
        changed(mapper.insertEvent(event));
    }

    public static Map<String, Object> publicView(AiCallRow row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("callId", row.getCallId());
        view.put("turnId", row.getTurnId());
        view.put("userId", Long.toString(row.getUserId()));
        view.put("departmentId", Long.toString(row.getDepartmentId()));
        view.put("mode", row.getMode());
        view.put("startedAt", row.getStartedAt().toString());
        view.put("generationStatus", row.getGenerationStatus());
        view.put("resultCode", row.getResultCode());
        view.put("usageStatus", row.getUsageStatus());
        view.put("promptTokens", decimal(row.getPromptTokens()));
        view.put("completionTokens", decimal(row.getCompletionTokens()));
        view.put("reservedTokens", decimal(row.getReservedTokens()));
        view.put("plannedChargedTokens", decimal(row.getPlannedChargedTokens()));
        view.put("settledChargedTokens", "SETTLED".equals(row.getSettlementStatus()) ? decimal(row.getPlannedChargedTokens()) : null);
        view.put("settlementPolicy", row.getSettlementPolicy());
        view.put("settlementStatus", row.getSettlementStatus());
        view.put("finalizedAt", row.getFinalizedAt() == null ? null : row.getFinalizedAt().toString());
        view.put("settledAt", row.getSettledAt() == null ? null : row.getSettledAt().toString());
        view.put("elapsedMs", row.getFinalizedAt() == null ? null : Long.toString(Math.max(0, Duration.between(row.getStartedAt(), row.getFinalizedAt()).toMillis())));
        return view;
    }

    private static String decimal(Long value) { return value == null ? null : value.toString(); }
    private void running(AiCallRow row) {
        if (!"RUNNING".equals(row.getGenerationStatus()) || ("SINGLE".equals(row.getMode())
                && (row.getLeaseUntil() == null || !row.getLeaseUntil().isAfter(clock.instant())))) throw conflict();
    }
    private static void changed(int count) { if (count != 1) throw conflict(); }
    public static ApiException conflict() { return ApiException.conflict("AI 观测状态冲突"); }
    public static ApiException invalid() { return ApiException.badRequest("AI 元数据无效"); }
}
