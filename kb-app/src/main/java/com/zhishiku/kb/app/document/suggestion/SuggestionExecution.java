package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import com.zhishiku.kb.common.suggestion.SuggestionProtocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class SuggestionExecution {
    private final SuggestionStore store;
    private final SuggestionMaterials materials;
    private final long ttlMinutes;

    public SuggestionExecution(SuggestionStore store, SuggestionMaterials materials,
                               @Value("${kb.metadata-suggestions.result-ttl-minutes:30}") long ttlMinutes) {
        if (ttlMinutes < 1 || ttlMinutes > 1440) {
            throw new IllegalArgumentException("建议有效期应介于 1 到 1440 分钟");
        }
        this.store = store;
        this.materials = materials;
        this.ttlMinutes = ttlMinutes;
    }

    public static SuggestionProtocol.Key key(SuggestionJob job) {
        return new SuggestionProtocol.Key(job.requestId(), job.executionId(), job.departmentId(),
                job.deadline().toInstant().toEpochMilli());
    }

    @Transactional
    public boolean action(String operation, String token, SuggestionProtocol.Action action) {
        var job = store.lock(action.key().requestId());
        if (job == null || !key(job).equals(action.key()) || !job.running()
                || job.deadline().toInstant().toEpochMilli() <= System.currentTimeMillis() || !store.activeSession(job.requestId(), token)) {
            return false;
        }
        if ("permit".equals(operation)) {
            materials.authorizePermit(token, job);
        } else {
            materials.authorize(token, job);
        }
        if (action.reservation() < 1) {
            return false;
        }
        if ("reserve".equals(operation)) {
            if (job.reservedTokens() != null) {
                return job.reservedTokens() == action.reservation() && job.dispatchedAt() == null
                        && ("RESERVING".equals(job.settlementStatus()) || "RESERVED".equals(job.settlementStatus()));
            }
            return store.reserve(job.requestId(), action.reservation());
        }
        if (!Long.valueOf(action.reservation()).equals(job.reservedTokens())) {
            return false;
        }
        return switch (operation) {
            case "reserved" -> "RESERVED".equals(job.settlementStatus()) || store.confirmReserve(job.requestId(), action.reservation());
            case "permit" -> store.dispatch(job.requestId());
            case "active" -> job.dispatchedAt() != null;
            default -> false;
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID id, UUID execution, String token, SuggestionProtocol.Outcome outcome) {
        var job = store.lock(id);
        if (job == null || !job.executionId().equals(execution)) {
            return;
        }
        if (job.usageStatus() != null) {
            if (reliable(outcome) && (!outcome.prompt().equals(job.promptTokens()) || !outcome.completion().equals(job.completionTokens()))) {
                store.recordLateUsage(id, outcome.prompt(), outcome.completion());
            }
            return;
        }
        String reason = outcome.reason();
        if (reason != null && !SuggestionProtocol.REASONS.contains(reason)) {
            reason = "UNAVAILABLE";
        }
        if (job.running()) {
            if (!job.deadline().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
                reason = "TIMEOUT";
            } else if (reason == null) {
                try {
                    materials.authorize(token, job);
                    if (job.dispatchedAt() == null || outcome.result() == null) {
                        reason = "INVALID_MODEL_OUTPUT";
                    }
                } catch (RuntimeException denied) {
                    reason = reason(denied);
                }
            }
            if (reason == null) {
                store.saveResult(id, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(ttlMinutes), outcome.result());
                store.terminate(id, "COMPLETED", null);
            } else {
                String status = "TIMEOUT".equals(reason) ? "INTERRUPTED"
                        : "QUOTA_REJECTED".equals(reason) ? "REJECTED" : "FAILED";
                store.terminate(id, status, reason);
            }
        }
        long reserved = job.reservedTokens() == null ? 0 : job.reservedTokens();
        boolean known = reliable(outcome);
        var usage = UsageSettlement.calculate(job.dispatchedAt() != null, known, outcome.prompt(), outcome.completion(), reserved);
        store.freeze(id, usage);
    }

    private static boolean reliable(SuggestionProtocol.Outcome outcome) {
        return outcome.prompt() != null && outcome.completion() != null && outcome.prompt() >= 0
                && outcome.completion() >= 0 && outcome.prompt() <= Long.MAX_VALUE - outcome.completion();
    }

    public static String reason(RuntimeException failed) {
        if (failed instanceof SuggestionMaterials.Rejection && SuggestionProtocol.REASONS.contains(failed.getMessage())) {
            return failed.getMessage();
        }
        if (failed instanceof MetadataException e && SuggestionProtocol.REASONS.contains(e.reason().name())) {
            return e.reason().name();
        }
        if (failed instanceof ApiException e && (e.httpStatus() == 401 || e.httpStatus() == 403 || e.httpStatus() == 404)) {
            return "FORBIDDEN";
        }
        return "UNAVAILABLE";
    }
}
