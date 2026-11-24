package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.assistance.SignedSearchContext;
import com.zhishiku.kb.app.search.assistance.TermCursor;
import com.zhishiku.kb.app.search.term.mapper.TermMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;

/** 所有管理目录及详情先鉴权，修订、行和目标来自同一次短只读快照。 */
@Service
public class TermReadService {
    private final TermDatabase database;
    private final TermAccess access;
    private final TermMapper mapper;
    private final TermCursor cursor;

    public TermReadService(TermDatabase database, TermAccess access, TermMapper mapper, TermCursor cursor) {
        this.database = database;
        this.access = access;
        this.mapper = mapper;
        this.cursor = cursor;
    }

    public TermViews.Page<TermViews.Term> terms(TermInputs.Query input) {
        return read(() -> {
            long actor = access.requireRead();
            TermRows.State state = state();
            var conditions = conditions(actor, SignedSearchContext.Purpose.TERM_DIRECTORY, input);
            var position = cursor.read(input.cursor(), conditions, state.revision());
            var rows = mapper.terms(TermInput.literalPattern(conditions.nameQuery()), input.enabled(),
                    position == null ? null : position.lastId(), input.limit() + 1);
            checkRows(rows, input.limit());
            boolean more = rows.size() > input.limit();
            var items = projectTerms(rows.subList(0, Math.min(rows.size(), input.limit())));
            String next = more ? cursor.issue(conditions, state.revision(), rows.get(input.limit() - 1).id()) : null;
            return new TermViews.Page<>(items, more, next, Long.toString(state.revision()));
        });
    }

    public TermViews.Page<TermViews.Correction> corrections(TermInputs.Query input) {
        return read(() -> {
            long actor = access.requireRead();
            TermRows.State state = state();
            var conditions = conditions(actor, SignedSearchContext.Purpose.CORRECTION_DIRECTORY, input);
            var position = cursor.read(input.cursor(), conditions, state.revision());
            var rows = mapper.corrections(TermInput.literalPattern(conditions.nameQuery()), input.enabled(),
                    position == null ? null : position.lastId(), input.limit() + 1);
            checkRows(rows, input.limit());
            boolean more = rows.size() > input.limit();
            var items = new ArrayList<TermViews.Correction>();
            for (TermRows.Correction row : rows.subList(0, Math.min(rows.size(), input.limit()))) {
                items.add(correction(row));
            }
            String next = more ? cursor.issue(conditions, state.revision(), rows.get(input.limit() - 1).id()) : null;
            return new TermViews.Page<>(items, more, next, Long.toString(state.revision()));
        });
    }

    public TermViews.TermDetail term(long id) {
        return read(() -> {
            access.requireRead();
            TermRows.State state = state();
            TermRows.Term row = mapper.term(id);
            if (row == null) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            if (row.id() != id) {
                throw unavailable();
            }
            return new TermViews.TermDetail(term(row), Long.toString(state.revision()));
        });
    }

    public TermViews.CorrectionDetail correction(long id) {
        return read(() -> {
            access.requireRead();
            TermRows.State state = state();
            TermRows.Correction row = mapper.correction(id);
            if (row == null) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            if (row.id() != id) {
                throw unavailable();
            }
            return new TermViews.CorrectionDetail(correction(row), Long.toString(state.revision()));
        });
    }

    private TermRows.State state() {
        TermRows.State state = mapper.state();
        if (state == null || state.revision() < 0 || state.databaseTime() == null) {
            throw unavailable();
        }
        return state;
    }

    private TermViews.Term term(TermRows.Term row) {
        return projectTerms(List.of(row)).getFirst();
    }

    /**
     * 同一快照内已读取的单组关系，不进行跨请求缓存。
     * @param term 当前组
     * @param aliases 已核对的有界别名
     */
    private record LoadedTerm(TermRows.Term term, List<TermRows.Alias> aliases) {
        private LoadedTerm {
            aliases = List.copyOf(aliases);
        }

        @Override
        public String toString() {
            return "LoadedTerm[redacted]";
        }
    }

    private LoadedTerm loadTerm(TermRows.Term row) {
        checkTerm(row);
        List<TermRows.Alias> aliases = mapper.aliases(row.id());
        if (aliases == null || aliases.size() > TermInput.ALIAS_LIMIT || aliases.stream().anyMatch(alias -> alias == null
                || alias.termId() != row.id() || alias.kind() == null || alias.aliasText() == null || alias.lookupKey() == null)) {
            throw unavailable();
        }
        return new LoadedTerm(row, aliases);
    }

    private List<TermViews.Term> projectTerms(List<TermRows.Term> rows) {
        List<LoadedTerm> loaded = rows.stream().map(this::loadTerm).toList();
        var keys = new LinkedHashSet<String>();
        for (LoadedTerm item : loaded) {
            keys.add(item.term().canonicalKey());
            item.aliases().forEach(alias -> keys.add(alias.lookupKey()));
        }
        Map<String, Boolean> flags = ambiguityFlags(List.copyOf(keys));
        return loaded.stream().map(item -> new TermViews.Term(Long.toString(item.term().id()), item.term().canonicalName(),
                item.term().description(), flags.get(item.term().canonicalKey()), item.aliases().stream()
                .map(alias -> new TermViews.Alias(alias.aliasText(), alias.kind(), flags.get(alias.lookupKey()))).toList(),
                item.term().enabled(), Long.toString(item.term().version()), item.term().createdAt(), item.term().updatedAt())).toList();
    }

    private Map<String, Boolean> ambiguityFlags(List<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        if (keys.size() > 2100) {
            throw unavailable();
        }
        List<TermRows.Expression> rows = mapper.ambiguities(keys);
        if (rows == null || rows.size() != keys.size()) {
            throw unavailable();
        }
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Set<String> expectedKeys = Set.copyOf(keys);
        for (TermRows.Expression row : rows) {
            if (row == null || row.lookupKey() == null || row.ambiguous() == null || !expectedKeys.contains(row.lookupKey())
                    || flags.put(row.lookupKey(), row.ambiguous()) != null) {
                throw unavailable();
            }
        }
        return Map.copyOf(flags);
    }

    public TermViews.Page<TermViews.Meaning> meanings(long targetId, TermInputs.MeaningQuery input) {
        return read(() -> {
            long actor = access.requireRead();
            TermRows.State state = state();
            TermRows.Term target = mapper.term(targetId);
            if (target == null) {
                throw new TermException(TermException.Reason.NOT_FOUND);
            }
            if (target.id() != targetId) {
                throw unavailable();
            }
            LoadedTerm loaded = loadTerm(target);
            String key = TermInput.key(input.expression());
            if (!target.canonicalKey().equals(key) && loaded.aliases().stream().noneMatch(alias -> alias.lookupKey().equals(key))) {
                throw TermInput.invalid();
            }
            var conditions = new TermCursor.Conditions(actor, SignedSearchContext.Purpose.TERM_MEANINGS, targetId, key, true, input.limit());
            var position = cursor.read(input.cursor(), conditions, state.revision());
            List<TermRows.Term> rows = mapper.meanings(key, position == null ? null : position.lastId(), input.limit() + 1);
            checkRows(rows, input.limit());
            boolean more = rows.size() > input.limit();
            List<TermViews.Meaning> items = rows.subList(0, Math.min(rows.size(), input.limit())).stream().map(row -> {
                checkTerm(row);
                if (!row.enabled()) {
                    throw unavailable();
                }
                return new TermViews.Meaning(Long.toString(row.id()), row.canonicalName(), row.description(), Long.toString(row.version()));
            }).toList();
            String next = more ? cursor.issue(conditions, state.revision(), rows.get(input.limit() - 1).id()) : null;
            return new TermViews.Page<>(items, more, next, Long.toString(state.revision()));
        });
    }

    private TermViews.Correction correction(TermRows.Correction row) {
        if (row.id() <= 0 || row.version() < 0 || row.targetTermId() <= 0 || row.wrongExpression() == null
                || row.createdAt() == null || row.updatedAt() == null) {
            throw unavailable();
        }
        TermRows.Term target = mapper.term(row.targetTermId());
        checkTerm(target);
        if (target.id() != row.targetTermId()) {
            throw unavailable();
        }
        var view = new TermViews.Target(Long.toString(target.id()), target.canonicalName(), target.description(), target.enabled(), Long.toString(target.version()));
        return new TermViews.Correction(Long.toString(row.id()), row.wrongExpression(), view, row.enabled(), row.available(target),
                Long.toString(row.version()), row.createdAt(), row.updatedAt());
    }

    private static TermCursor.Conditions conditions(long actor, SignedSearchContext.Purpose purpose, TermInputs.Query input) {
        return new TermCursor.Conditions(actor, purpose, null, input.nameQuery(), input.enabled(), input.limit());
    }

    private static void checkRows(List<?> rows, int limit) {
        if (rows == null || rows.size() > limit + 1) {
            throw unavailable();
        }
    }

    private static void checkTerm(TermRows.Term row) {
        if (row == null || row.id() <= 0 || row.version() < 0 || row.canonicalName() == null || row.canonicalKey() == null || row.description() == null
                || row.createdAt() == null || row.updatedAt() == null) {
            throw unavailable();
        }
    }

    private <T> T read(Supplier<T> action) {
        try {
            return database.read(action);
        } catch (SearchContextFailure failure) {
            throw new TermException(switch (failure.kind()) {
                case INVALID_INPUT -> TermException.Reason.INVALID_INPUT;
                case DICTIONARY_CHANGED -> TermException.Reason.DICTIONARY_CHANGED;
                default -> TermException.Reason.UNAVAILABLE;
            });
        }
    }

    private static TermException unavailable() {
        return new TermException(TermException.Reason.UNAVAILABLE);
    }
}
