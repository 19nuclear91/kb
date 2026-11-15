package com.zhishiku.kb.app.document.stewardship.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityCommands;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipInputs;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipRow;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipSchedule;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.STEWARD_INELIGIBLE;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.STEWARDSHIP_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class StewardshipWriteService {
    private final StewardshipMapper mapper;
    private final StewardshipAccess access;
    private final ValidityCommands commands;
    private final IdentityAuditService audit;
    private final BusinessDate date;

    public StewardshipWriteService(StewardshipMapper mapper, StewardshipAccess access, ValidityCommands commands,
                                   IdentityAuditService audit, BusinessDate date) {
        this.mapper = mapper;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
        this.date = date;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StewardshipViews.Receipt update(long actor, long document, StewardshipInputs.Update input, String requestId) {
        var binding = commands.bind(AuditScope.DOCUMENT_STEWARDSHIP_UPDATE, requestId, actor, document, document,
                input.expectedStewardshipVersion(), input);
        var previous = commands.lock(binding);
        if (mapper.shareDocument(document) == null) {
            throw new ValidityException(NOT_FOUND);
        }
        access.manager(actor, document, true);
        var before = mapper.lock(document);
        access.manager(actor, document, true);
        if (previous != null) {
            return commands.receipt(previous, StewardshipViews.Receipt.class).replay();
        }
        long version = before == null ? 0 : before.stewardshipVersion();
        if (version != input.expectedStewardshipVersion() || version == Long.MAX_VALUE) {
            throw new ValidityException(STEWARDSHIP_VERSION_CONFLICT);
        }
        Long originalResponsible = before == null ? null : before.responsibleUserId();
        if (input.responsibleUserId() != null && !mapper.eligible(document, input.responsibleUserId())) {
            throw new ValidityException(STEWARD_INELIGIBLE);
        }
        if (Objects.equals(originalResponsible, input.responsibleUserId())
                && (input.responsibleUserId() == null || (before != null && Objects.equals(before.cycleMonths(), input.cycleMonths())))) {
            throw new ValidityException(NO_CHANGE);
        }
        LocalDate today = date.today();
        var schedule = input.responsibleUserId() == null ? before.schedule()
                : StewardshipSchedule.assign(before == null ? null : before.schedule(), input.cycleMonths(), today);
        var after = new StewardshipRow(document, input.responsibleUserId(), schedule.cycleMonths(), schedule.firstAssignedOn(),
                schedule.lastConfirmedOn(), before == null ? null : before.lastConfirmedBy(), schedule.dueDate(), version + 1, null);
        if ((before == null ? mapper.insert(after) : mapper.update(after)) != 1) {
            throw new ValidityException(STEWARDSHIP_VERSION_CONFLICT);
        }
        var saved = mapper.get(document);
        if (saved == null || saved.stewardshipVersion() != version + 1) {
            throw new ValidityException(UNAVAILABLE);
        }
        String operation = input.responsibleUserId() == null ? "unassign" : originalResponsible == null ? "assign"
                : !originalResponsible.equals(input.responsibleUserId()) ? "reassign" : "cycle";
        var receipt = new StewardshipViews.Receipt(document, saved.stewardshipVersion(), saved.dueDate(), saved.lastConfirmedOn(), saved.updatedAt(), today, false);
        audit.appendSuccess(binding.scope(), binding.requestId(), actor, document, operation, commands.write(summary(before)), commands.write(summary(saved)));
        commands.save(binding, receipt);
        return receipt;
    }

    static Map<String, Object> summary(StewardshipRow row) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("responsibleUserId", row == null ? null : row.responsibleUserId());
        fields.put("cycleMonths", row == null ? null : row.cycleMonths());
        fields.put("lastConfirmedOn", row == null ? null : row.lastConfirmedOn());
        fields.put("dueDate", row == null ? null : row.dueDate());
        fields.put("stewardshipVersion", row == null ? 0 : row.stewardshipVersion());
        return fields;
    }
}
