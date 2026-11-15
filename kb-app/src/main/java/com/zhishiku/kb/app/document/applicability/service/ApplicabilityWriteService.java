package com.zhishiku.kb.app.document.applicability.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityChange;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityRow;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityUpdate;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.APPLICABILITY_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REASON_REQUIRED;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.REVIEW_IN_PROGRESS;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class ApplicabilityWriteService {
    private final ApplicabilityMapper mapper;
    private final ValidityAccess access;
    private final ValidityCommands commands;
    private final IdentityAuditService audit;
    private final BusinessDate date;

    public ApplicabilityWriteService(ApplicabilityMapper mapper, ValidityAccess access, ValidityCommands commands,
                                     IdentityAuditService audit, BusinessDate date) {
        this.mapper = mapper;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
        this.date = date;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApplicabilityViews.Receipt update(long actor, long document, long version, ApplicabilityUpdate input, String requestId) {
        input.declaration();
        var binding = commands.bind(AuditScope.DOCUMENT_APPLICABILITY_UPDATE, requestId, actor, version, document,
                input.expectedApplicabilityVersion(), input);
        var previous = commands.lock(binding);
        if (mapper.lockVersion(document, version) == null) {
            throw new ValidityException(NOT_FOUND);
        }
        var handle = access.current(actor);
        access.editable(handle, document, version);
        // 锁等待前的语句快照不能用于审核冻结与发布类别判定；锁取得后再读一次。
        var target = mapper.target(document, version);
        if (target == null || target.spaceArchived() || "archived".equals(target.documentStatus())) {
            throw new ValidityException(NOT_FOUND);
        }
        if (previous != null) {
            return commands.receipt(previous, ApplicabilityViews.Receipt.class).replay();
        }
        boolean visible = target.visibleToReaders();
        if (!visible && target.reviewInProgress()) {
            throw new ValidityException(REVIEW_IN_PROGRESS);
        }
        if (visible && input.reason() == null) {
            throw new ValidityException(REASON_REQUIRED);
        }
        var before = mapper.get(document, version);
        if (before == null) {
            throw new ValidityException(NOT_FOUND);
        }
        if (before.applicabilityVersion() != input.expectedApplicabilityVersion() || before.applicabilityVersion() == Long.MAX_VALUE) {
            throw new ValidityException(APPLICABILITY_VERSION_CONFLICT);
        }
        if (before.declaration().equals(input.declaration())) {
            throw new ValidityException(NO_CHANGE);
        }
        LocalDate today = date.today();
        var next = new ApplicabilityRow(document, version, input.effectiveFrom(), input.untilKind(), input.effectiveUntil(),
                before.applicabilityVersion() + 1, before.corrected() || visible, null, before.current());
        int written = before.applicabilityVersion() == 0 ? mapper.insert(next) : mapper.update(next);
        if (written != 1) {
            throw new ValidityException(APPLICABILITY_VERSION_CONFLICT);
        }
        if (mapper.appendChange(new ApplicabilityChange(0, document, version, null, before.effectiveFrom(), before.untilKind(),
                before.effectiveUntil(), next.effectiveFrom(), next.untilKind(), next.effectiveUntil(), visible, input.reason())) != 1) {
            throw new ValidityException(UNAVAILABLE);
        }
        var after = mapper.get(document, version);
        if (after == null || after.applicabilityVersion() != next.applicabilityVersion()) {
            throw new ValidityException(UNAVAILABLE);
        }
        var receipt = new ApplicabilityViews.Receipt(document, version, after.applicabilityVersion(), after.corrected(), after.updatedAt(), today, false);
        audit.appendSuccess(binding.scope(), binding.requestId(), actor, version, "update",
                commands.write(summary(before, visible, false)), commands.write(summary(after, visible, input.reason() != null)));
        commands.save(binding, receipt);
        return receipt;
    }

    private static Map<String, Object> summary(ApplicabilityRow row, boolean visible, boolean reasonProvided) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("documentId", row.documentId());
        fields.put("versionId", row.versionId());
        fields.put("effectiveFrom", row.effectiveFrom());
        fields.put("untilKind", row.untilKind());
        fields.put("effectiveUntil", row.effectiveUntil());
        fields.put("applicabilityVersion", row.applicabilityVersion());
        fields.put("visibleToReaders", visible);
        fields.put("reasonProvided", reasonProvided);
        return fields;
    }
}
