package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static com.zhishiku.kb.app.search.term.TermException.Reason.EXPRESSION_CONFLICT;
import static com.zhishiku.kb.app.search.term.TermException.Reason.NAME_CONFLICT;
import static com.zhishiku.kb.app.search.term.TermException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.search.term.TermException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.search.term.TermException.Reason.UNAVAILABLE;
import static com.zhishiku.kb.app.search.term.TermException.Reason.VERSION_CONFLICT;

/** 低频术语维护统一串行于修订行，业务、版本、回执及审计在一个短事务内完成。 */
@Service
public class TermWriteService {
    private final TermDatabase database;
    private final TermAccess access;
    private final TermMapper mapper;
    private final TermCommands commands;
    private final TermAudit audit;

    public TermWriteService(TermDatabase database, TermAccess access, TermMapper mapper, TermCommands commands, TermAudit audit) {
        this.database = database;
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.audit = audit;
    }

    public TermReceipt write(Long id, TermInputs.TermWrite input, String requestId) {
        return database.write(() -> {
            long actor = access.requireWrite();
            TermInputs.TermWrite normalized = TermInput.normalized(input, id == null);
            var binding = commands.bind(id == null ? TermRows.Operation.SEARCH_TERM_CREATE : TermRows.Operation.SEARCH_TERM_UPDATE,
                    requestId, actor, id, normalized.expectedVersion(), normalized);
            var previous = commands.lock(binding);
            long revision = lockRevision();
            access.recheckWriter(actor);
            if (previous != null) {
                return commands.receipt(previous).asReplay();
            }
            TermRows.Term before = id == null ? null : mapper.term(id);
            if (id != null && before == null) {
                throw new TermException(NOT_FOUND);
            }
            if (before != null && !Objects.equals(normalized.expectedVersion(), before.version())) {
                throw new TermException(VERSION_CONFLICT);
            }
            var previousAliases = before == null ? List.<TermInputs.Alias>of() : aliases(id);
            List<String> changed = changes(before, previousAliases, normalized);
            if (changed.isEmpty()) {
                throw new TermException(NO_CHANGE);
            }
            checkKeys(id, normalized);
            long version = before == null ? 0 : increment(before.version());
            long nextRevision = increment(revision);
            TermRows.Term after;
            try {
                if (before == null) {
                    after = mapper.insertTerm(normalized);
                    if (after == null || after.id() <= 0 || after.version() != 0 || !after.enabled()) {
                        throw new TermException(UNAVAILABLE);
                    }
                } else {
                    if (mapper.updateTerm(id, normalized) != 1) {
                        throw new TermException(VERSION_CONFLICT);
                    }
                    mapper.clearAliases(id);
                    after = new TermRows.Term(id, normalized.canonicalName(), normalized.canonicalKey(), normalized.description(),
                            before.enabled(), version, before.createdAt(), null);
                }
                for (TermInputs.Alias alias : normalized.aliases()) {
                    if (mapper.insertAlias(after.id(), alias) != 1) {
                        throw new TermException(UNAVAILABLE);
                    }
                }
            } catch (DuplicateKeyException conflict) {
                throw new TermException(NAME_CONFLICT);
            }
            advance(revision);
            var receipt = new TermReceipt(TermRows.EntityKind.TERM, after.id(), version, nextRevision, false);
            commands.save(binding, receipt);
            audit.term(binding, before, after, revision, nextRevision, changed);
            return receipt;
        });
    }

    public TermReceipt enabled(long id, TermInputs.EnabledWrite input, String requestId) {
        return database.write(() -> {
            long actor = access.requireWrite();
            if (input == null || input.expectedVersion() < 0) {
                throw TermInput.invalid();
            }
            var binding = commands.bind(TermRows.Operation.SEARCH_TERM_ENABLED, requestId, actor, id, input.expectedVersion(), input);
            var previous = commands.lock(binding);
            long revision = lockRevision();
            access.recheckWriter(actor);
            if (previous != null) {
                return commands.receipt(previous).asReplay();
            }
            TermRows.Term before = mapper.term(id);
            if (before == null) {
                throw new TermException(NOT_FOUND);
            }
            if (before.version() != input.expectedVersion()) {
                throw new TermException(VERSION_CONFLICT);
            }
            if (before.enabled() == input.enabled()) {
                throw new TermException(NO_CHANGE);
            }
            long version = increment(before.version());
            long nextRevision = increment(revision);
            if (mapper.enableTerm(id, input) != 1) {
                throw new TermException(VERSION_CONFLICT);
            }
            advance(revision);
            var after = new TermRows.Term(id, before.canonicalName(), before.canonicalKey(), before.description(), input.enabled(),
                    version, before.createdAt(), null);
            var receipt = new TermReceipt(TermRows.EntityKind.TERM, id, version, nextRevision, false);
            commands.save(binding, receipt);
            audit.term(binding, before, after, revision, nextRevision, List.of("enabled"));
            return receipt;
        });
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

    private void checkKeys(Long id, TermInputs.TermWrite input) {
        Long owner = mapper.canonicalOwner(input.canonicalKey());
        if (owner != null && !Objects.equals(owner, id)) {
            throw new TermException(NAME_CONFLICT);
        }
        if (mapper.correctionKeyExists(input.canonicalKey())) {
            throw new TermException(EXPRESSION_CONFLICT);
        }
        for (TermInputs.Alias alias : input.aliases()) {
            if (mapper.correctionKeyExists(alias.key())) {
                throw new TermException(EXPRESSION_CONFLICT);
            }
        }
    }

    private List<TermInputs.Alias> aliases(long id) {
        List<TermRows.Alias> rows = mapper.aliases(id);
        if (rows == null || rows.size() > TermInput.ALIAS_LIMIT) {
            throw new TermException(UNAVAILABLE);
        }
        return rows.stream().map(row -> new TermInputs.Alias(row.aliasText(), row.lookupKey(), row.kind()))
                .sorted(Comparator.comparing(TermInputs.Alias::key).thenComparing(alias -> alias.kind().name()).thenComparing(TermInputs.Alias::text)).toList();
    }

    private static List<String> changes(TermRows.Term before, List<TermInputs.Alias> previous, TermInputs.TermWrite input) {
        if (before == null) {
            return List.of("canonicalName", "description", "aliases", "enabled");
        }
        var fields = new ArrayList<String>();
        if (!before.canonicalName().equals(input.canonicalName())) {
            fields.add("canonicalName");
        }
        if (!before.description().equals(input.description())) {
            fields.add("description");
        }
        if (!previous.equals(input.aliases())) {
            fields.add("aliases");
        }
        return List.copyOf(fields);
    }

    static long increment(long value) {
        if (value < 0 || value == Long.MAX_VALUE) {
            throw new TermException(UNAVAILABLE);
        }
        return value + 1;
    }
}
