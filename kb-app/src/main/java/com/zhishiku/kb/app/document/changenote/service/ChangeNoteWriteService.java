package com.zhishiku.kb.app.document.changenote.service;

import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.changenote.mapper.ChangeNoteMapper;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteHistory;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteInput;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteRow;
import com.zhishiku.kb.app.document.changenote.model.ChangeNoteViews.Receipt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.REVIEW_IN_PROGRESS;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.REASON_REQUIRED;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NOTE_REMOVAL_NOT_ALLOWED;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NO_BASE_VERSION;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NOTE_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.changenote.service.ChangeNoteException.Reason.UNAVAILABLE;

@Service
public class ChangeNoteWriteService {
    private final ChangeNoteMapper mapper;
    private final ChangeNoteAccess access;
    private final ChangeNoteCommands commands;
    private final IdentityAuditService audit;

    public ChangeNoteWriteService(ChangeNoteMapper mapper, ChangeNoteAccess access, ChangeNoteCommands commands,
                                  IdentityAuditService audit) {
        this.mapper = mapper;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Receipt update(long actor, long document, long version, ChangeNoteInput input, String requestId) {
        var binding = commands.bind(requestId, actor, document, version, input);
        var previous = commands.lock(binding);
        if (mapper.lockVersion(document, version) == null) {
            throw new ChangeNoteException(NOT_FOUND);
        }
        access.editable(access.current(actor), document, version);
        var target = mapper.target(document, version);
        if (target == null || target.spaceArchived() || "archived".equals(target.documentStatus())
                || "deleted".equals(target.documentStatus())) {
            throw new ChangeNoteException(NOT_FOUND);
        }
        if (previous != null) {
            return commands.receipt(previous).replay();
        }
        boolean visible = target.visibleToReaders();
        if (!visible && target.reviewInProgress()) {
            throw new ChangeNoteException(REVIEW_IN_PROGRESS);
        }
        var before = mapper.get(document, version);
        if (before == null) {
            throw new ChangeNoteException(NOT_FOUND);
        }
        if (visible && input.text() == null) {
            throw new ChangeNoteException(NOTE_REMOVAL_NOT_ALLOWED);
        }
        if (visible && before.hasNote() && input.reason() == null) {
            throw new ChangeNoteException(REASON_REQUIRED);
        }
        ChangeNoteMapper.Base base = visible && before.hasNote()
                ? new ChangeNoteMapper.Base(before.baseVersionId(), before.baseVersionNo()) : mapper.base(document, version);
        if (base == null) {
            throw new ChangeNoteException(NO_BASE_VERSION);
        }
        Long baseId = input.text() == null ? null : base.versionId();
        if (before.noteVersion() != input.expectedNoteVersion() || before.noteVersion() == Long.MAX_VALUE) {
            throw new ChangeNoteException(NOTE_VERSION_CONFLICT);
        }
        if (Objects.equals(before.text(), input.text()) && Objects.equals(before.baseVersionId(), baseId)) {
            throw new ChangeNoteException(NO_CHANGE);
        }
        var next = new ChangeNoteRow(document, version, input.text(), baseId, baseId == null ? null : base.versionNo(),
                before.noteVersion() + 1, before.corrected() || visible && before.hasNote(),
                before.createdAfterVisible() || visible && !before.hasNote(), null);
        int written = before.noteVersion() == 0 ? mapper.insert(next) : mapper.update(next);
        if (written != 1) {
            throw new ChangeNoteException(NOTE_VERSION_CONFLICT);
        }
        if (mapper.appendHistory(new ChangeNoteHistory(0, document, version, null, before.text(), next.text(),
                before.baseVersionId(), next.baseVersionId(), visible, input.reason())) != 1) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
        var after = mapper.get(document, version);
        if (after == null || after.noteVersion() != next.noteVersion()) {
            throw new ChangeNoteException(UNAVAILABLE);
        }
        var receipt = new Receipt(document, version, after.noteVersion(), after.corrected(), after.createdAfterVisible(),
                after.updatedAt(), false);
        audit.appendSuccess(binding.scope(), binding.requestId(), actor, version, "update",
                commands.write(summary(before, visible, false)), commands.write(summary(after, visible, input.reason() != null)));
        commands.save(binding, receipt);
        return receipt;
    }

    private static Map<String, Object> summary(ChangeNoteRow row, boolean visible, boolean reason) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("documentId", row.documentId());
        fields.put("versionId", row.versionId());
        fields.put("baseVersionId", row.baseVersionId());
        fields.put("hasNote", row.hasNote());
        fields.put("noteVersion", row.noteVersion());
        fields.put("visibleToReaders", visible);
        fields.put("reasonProvided", reason);
        return fields;
    }
}
