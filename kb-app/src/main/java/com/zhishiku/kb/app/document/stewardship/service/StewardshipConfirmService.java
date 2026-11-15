package com.zhishiku.kb.app.document.stewardship.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityCommands;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.STEWARDSHIP_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class StewardshipConfirmService {
    private final StewardshipMapper mapper;
    private final ValidityAccess access;
    private final ValidityCommands commands;
    private final IdentityAuditService audit;
    private final BusinessDate date;

    public StewardshipConfirmService(StewardshipMapper mapper, ValidityAccess access, ValidityCommands commands,
                                     IdentityAuditService audit, BusinessDate date) {
        this.mapper = mapper;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
        this.date = date;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StewardshipViews.Receipt confirm(long actor, long document, StewardshipInputs.Confirm input, String requestId) {
        var binding = commands.bind(AuditScope.DOCUMENT_STEWARDSHIP_CONFIRM, requestId, actor, document, document,
                input.expectedStewardshipVersion(), input);
        var previous = commands.lock(binding);
        if (mapper.shareDocument(document) == null) {
            throw new ValidityException(NOT_FOUND);
        }
        var before = mapper.lock(document);
        var handle = access.current(actor);
        var target = mapper.document(document);
        if (target == null || target.spaceArchived() || "archived".equals(target.status())) {
            throw new ValidityException(NOT_FOUND);
        }
        // 改派先完成时旧责任人按当前资格拒绝；不得为统一竞争错误码而提前暴露版本或旧回执。
        if (before == null || !Objects.equals(before.responsibleUserId(), actor) || !mapper.eligible(document, actor)) {
            throw new ValidityException(FORBIDDEN);
        }
        if (!access.has(handle, document, PermissionService.PERM_VIEW)) {
            throw new ValidityException(NOT_FOUND);
        }
        if (previous != null) {
            return commands.receipt(previous, StewardshipViews.Receipt.class).replay();
        }
        if (before.stewardshipVersion() != input.expectedStewardshipVersion() || before.stewardshipVersion() == Long.MAX_VALUE) {
            throw new ValidityException(STEWARDSHIP_VERSION_CONFLICT);
        }
        var today = date.today();
        var schedule = before.schedule().confirm(today);
        var after = new StewardshipRow(document, actor, before.cycleMonths(), before.firstAssignedOn(), today, actor,
                schedule.dueDate(), before.stewardshipVersion() + 1, null);
        if (mapper.update(after) != 1) {
            throw new ValidityException(STEWARDSHIP_VERSION_CONFLICT);
        }
        var saved = mapper.get(document);
        if (saved == null || saved.stewardshipVersion() != after.stewardshipVersion()) {
            throw new ValidityException(UNAVAILABLE);
        }
        var receipt = new StewardshipViews.Receipt(document, saved.stewardshipVersion(), saved.dueDate(), saved.lastConfirmedOn(), saved.updatedAt(), today, false);
        audit.appendSuccess(binding.scope(), binding.requestId(), actor, document, "confirm",
                commands.write(StewardshipWriteService.summary(before)), commands.write(StewardshipWriteService.summary(saved)));
        commands.save(binding, receipt);
        return receipt;
    }
}
