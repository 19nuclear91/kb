package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.zhishiku.kb.app.search.term.TermException.Reason.EXPRESSION_CONFLICT;
import static com.zhishiku.kb.app.search.term.TermException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.search.term.TermException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.search.term.TermException.Reason.TARGET_UNAVAILABLE;
import static com.zhishiku.kb.app.search.term.TermException.Reason.UNAVAILABLE;
import static com.zhishiku.kb.app.search.term.TermException.Reason.VERSION_CONFLICT;

/** 单向错词维护；内容写入与重新启用要求目标启用，独立停用不重分配目标。 */
@Service
public class CorrectionWriteService {
    private final TermDatabase database;
    private final TermAccess access;
    private final TermMapper mapper;
    private final TermCommands commands;
    private final TermAudit audit;

    public CorrectionWriteService(TermDatabase database, TermAccess access, TermMapper mapper, TermCommands commands, TermAudit audit) {
        this.database = database;
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.audit = audit;
    }

    public TermReceipt write(Long id, TermInputs.CorrectionWrite input, String requestId) {
        return database.write(() -> {
            long actor = access.requireWrite();
            var normalized = TermInput.normalized(input, id == null);
            var binding = commands.bind(id == null ? TermRows.Operation.SEARCH_CORRECTION_CREATE : TermRows.Operation.SEARCH_CORRECTION_UPDATE,
                    requestId, actor, id, normalized.expectedVersion(), normalized);
            var previous = commands.lock(binding);
            long revision = lockRevision();
            access.recheckWriter(actor);
            if (previous != null) {
                return commands.receipt(previous).asReplay();
            }
            var before = id == null ? null : mapper.correction(id);
            if (id != null && before == null) {
                throw new TermException(NOT_FOUND);
            }
            if (before != null && !Objects.equals(normalized.expectedVersion(), before.version())) {
                throw new TermException(VERSION_CONFLICT);
            }
            List<String> changed = changes(before, normalized);
            if (changed.isEmpty()) {
                throw new TermException(NO_CHANGE);
            }
            Long owner = mapper.correctionOwner(normalized.wrongKey());
            if ((owner != null && !Objects.equals(owner, id)) || mapper.termKeyExists(normalized.wrongKey())) {
                throw new TermException(EXPRESSION_CONFLICT);
            }
            requireTarget(normalized.termId());
            long version = before == null ? 0 : TermWriteService.increment(before.version());
            long nextRevision = TermWriteService.increment(revision);
            TermRows.Correction after;
            try {
                if (before == null) {
                    after = mapper.insertCorrection(normalized);
                    if (after == null || after.id() <= 0 || after.version() != 0 || !after.enabled()) {
                        throw new TermException(UNAVAILABLE);
                    }
                } else {
                    if (mapper.updateCorrection(id, normalized) != 1) {
                        throw new TermException(VERSION_CONFLICT);
                    }
                    after = new TermRows.Correction(id, normalized.wrongExpression(), normalized.wrongKey(), normalized.termId(),
                            before.enabled(), version, before.createdAt(), null);
                }
            } catch (DuplicateKeyException conflict) {
                throw new TermException(EXPRESSION_CONFLICT);
            }
            advance(revision);
            var receipt = new TermReceipt(TermRows.EntityKind.CORRECTION, after.id(), version, nextRevision, false);
            commands.save(binding, receipt);
            audit.correction(binding, before, after, revision, nextRevision, changed);
            return receipt;
        });
    }

    public TermReceipt enabled(long id, TermInputs.EnabledWrite input, String requestId) {
        return database.write(() -> {
            long actor = access.requireWrite();
            if (input == null || input.expectedVersion() < 0) {
                throw TermInput.invalid();
            }
            var binding = commands.bind(TermRows.Operation.SEARCH_CORRECTION_ENABLED, requestId, actor, id, input.expectedVersion(), input);
            var previous = commands.lock(binding);
            long revision = lockRevision();
            access.recheckWriter(actor);
            if (previous != null) {
                return commands.receipt(previous).asReplay();
            }
            var before = mapper.correction(id);
            if (before == null) {
                throw new TermException(NOT_FOUND);
            }
            if (before.version() != input.expectedVersion()) {
                throw new TermException(VERSION_CONFLICT);
            }
            if (before.enabled() == input.enabled()) {
                throw new TermException(NO_CHANGE);
            }
            if (input.enabled()) {
                requireTarget(before.targetTermId());
            }
            long version = TermWriteService.increment(before.version());
            long nextRevision = TermWriteService.increment(revision);
            if (mapper.enableCorrection(id, input) != 1) {
                throw new TermException(VERSION_CONFLICT);
            }
            advance(revision);
            var after = new TermRows.Correction(id, before.wrongExpression(), before.wrongKey(), before.targetTermId(), input.enabled(),
                    version, before.createdAt(), null);
            var receipt = new TermReceipt(TermRows.EntityKind.CORRECTION, id, version, nextRevision, false);
            commands.save(binding, receipt);
            audit.correction(binding, before, after, revision, nextRevision, List.of("enabled"));
            return receipt;
        });
    }

    private void requireTarget(long targetId) {
        TermRows.Term target = mapper.term(targetId);
        if (target == null || !target.enabled()) {
            throw new TermException(TARGET_UNAVAILABLE);
        }
    }

    private long lockRevision() {
        Long revision = mapper.lockState();
        if (revision == null || revision < 0) {
            throw new TermException(UNAVAILABLE);
        }
        return revision;
    }

    private void advance(long revision) {
        if (mapper.advanceRevision(revision) != 1) {
            throw new TermException(UNAVAILABLE);
        }
    }

    private static List<String> changes(TermRows.Correction before, TermInputs.CorrectionWrite input) {
        if (before == null) {
            return List.of("wrongExpression", "targetTermId", "enabled");
        }
        var fields = new ArrayList<String>();
        if (!before.wrongExpression().equals(input.wrongExpression())) {
            fields.add("wrongExpression");
        }
        if (before.targetTermId() != input.termId()) {
            fields.add("targetTermId");
        }
        return List.copyOf(fields);
    }
}
