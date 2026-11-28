package com.zhishiku.kb.app.service.impl.health;

import com.zhishiku.kb.app.document.applicability.model.ApplicabilityDeclaration;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityState;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipState;
import com.zhishiku.kb.app.domain.dto.health.HealthRow;
import com.zhishiku.kb.app.domain.dto.health.HealthTotals;
import com.zhishiku.kb.app.domain.vo.health.KnowledgeHealthRadarVo;
import com.zhishiku.kb.app.enums.health.HealthSignal;
import com.zhishiku.kb.app.service.health.HealthFailure;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 校验最小事实与既有状态规则一致后构造响应，不以降级隐藏矛盾。 */
public final class HealthProjection {
    private HealthProjection() {
    }

    public static KnowledgeHealthRadarVo.Counts counts(HealthTotals totals) {
        var values = List.of(totals.stewardUnassigned(), totals.stewardNeedsReassign(), totals.reviewOverdue(),
                totals.reviewDueSoon(), totals.applicabilityExpired(), totals.applicabilityExpiringSoon());
        BigInteger affected = number(totals.affectedDocumentCount());
        BigInteger sum = BigInteger.ZERO;
        BigInteger management = BigInteger.ZERO;
        int index = 0;
        for (String value : values) {
            BigInteger count = number(value);
            if (count.compareTo(affected) > 0) {
                throw HealthFailure.unavailable();
            }
            sum = sum.add(count);
            if (index++ < 4) {
                management = management.add(count);
            }
        }
        if (sum.compareTo(affected) < 0 || management.compareTo(affected) > 0 || sum.subtract(management).compareTo(affected) > 0) {
            throw HealthFailure.unavailable();
        }
        return new KnowledgeHealthRadarVo.Counts(values.get(0), values.get(1), values.get(2), values.get(3), values.get(4), values.get(5));
    }

    public static KnowledgeHealthRadarVo.Item item(HealthRow row, LocalDate today) {
        if (row.documentId() <= 0 || row.responsibleUserId() != null
                && (row.responsibleUserId() <= 0 || row.displayName() == null)) {
            throw HealthFailure.unavailable();
        }
        var state = StewardshipState.of(row.responsibleUserId(), row.eligible(), row.dueDate(), today);
        if (!state.name().equals(row.stewardshipState())) {
            throw HealthFailure.unavailable();
        }
        var signals = new ArrayList<HealthSignal>();
        switch (state) {
            case UNASSIGNED -> {
                signals.add(HealthSignal.STEWARD_UNASSIGNED);
            }
            case NEEDS_REASSIGN -> {
                signals.add(HealthSignal.STEWARD_NEEDS_REASSIGN);
            }
            case OVERDUE -> {
                signals.add(HealthSignal.REVIEW_OVERDUE);
            }
            case DUE_SOON -> {
                signals.add(HealthSignal.REVIEW_DUE_SOON);
            }
            default -> {
            }
        }
        KnowledgeHealthRadarVo.Applicability applicability = null;
        if (row.versionId() != null) {
            if (row.versionId() <= 0) {
                throw HealthFailure.unavailable();
            }
            var actual = ApplicabilityState.of(new ApplicabilityDeclaration(row.effectiveFrom(), row.untilKind(), row.effectiveUntil()), today);
            if (!actual.name().equals(row.applicabilityState())) {
                throw HealthFailure.unavailable();
            }
            if (actual == ApplicabilityState.EXPIRED || actual == ApplicabilityState.EXPIRING_SOON) {
                signals.add(actual == ApplicabilityState.EXPIRED ? HealthSignal.APPLICABILITY_EXPIRED : HealthSignal.APPLICABILITY_EXPIRING_SOON);
                applicability = new KnowledgeHealthRadarVo.Applicability(Long.toString(row.versionId()), actual.name(), row.effectiveFrom(), row.effectiveUntil());
            }
        } else if (row.applicabilityState() != null || row.effectiveFrom() != null || row.effectiveUntil() != null || row.untilKind() != null) {
            throw HealthFailure.unavailable();
        }
        if (signals.isEmpty()) {
            throw HealthFailure.unavailable();
        }
        var responsible = row.responsibleUserId() == null ? null
                : new KnowledgeHealthRadarVo.Responsible(Long.toString(row.responsibleUserId()), row.displayName());
        return new KnowledgeHealthRadarVo.Item(Long.toString(row.documentId()), List.copyOf(signals),
                new KnowledgeHealthRadarVo.Stewardship(state.name(), responsible, row.dueDate()), applicability);
    }

    public static BigInteger number(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}") || new BigInteger(value).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw HealthFailure.unavailable();
        }
        return new BigInteger(value);
    }
}
