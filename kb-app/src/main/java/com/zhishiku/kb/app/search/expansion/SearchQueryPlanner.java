package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.model.SearchInput;
import com.zhishiku.kb.app.search.term.TermException;
import com.zhishiku.kb.app.search.term.TermInput;
import com.zhishiku.kb.app.search.term.TermRows;
import com.zhishiku.kb.app.search.term.TermSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 纯查询计划器；调用方先完成身份及建议绑定核验，本组件重新核验所有选择的候选事实。 */
public final class SearchQueryPlanner {
    public static final int SELECTION_LIMIT = 8;
    public static final int EXPRESSION_LIMIT = 32;
    public static final int ADDED_CODEPOINT_LIMIT = 2048;

    private record Applied(TermMatcher.Match match, TermSnapshot.Meaning meaning) {
        @Override
        public String toString() {
            return "PlannedAppliedTerm[redacted]";
        }
    }

    /** ORIGINAL 提前返回，不访问快照、规范化匹配或任何凭据服务。 */
    public SearchQueryPlan plan(SearchQueryPlan.Mode mode, String original, int maxCodepoints,
                                 List<SearchQueryPlan.Selection> selections, TermSnapshot snapshot, Runnable checkpoint) {
        Objects.requireNonNull(checkpoint);
        checkpoint.run();
        if (mode == null || selections == null) {
            throw invalid();
        }
        SearchInput.validate(original, 1, 1, maxCodepoints);
        if (mode == SearchQueryPlan.Mode.ORIGINAL) {
            if (!selections.isEmpty()) {
                throw invalid();
            }
            checkpoint.run();
            return new SearchQueryPlan(mode, original, original, original, null, List.of(), List.of(), List.of());
        }
        var query = NormalizedQuery.create(original, maxCodepoints, checkpoint);
        List<SearchQueryPlan.Selection> ordered = structuralSelections(selections, query.originalLength(), checkpoint);
        Objects.requireNonNull(snapshot);
        TermRows.State state = snapshot.state();
        if (state == null || state.revision() < 0 || state.databaseTime() == null) {
            throw unavailable();
        }
        List<TermMatcher.Match> matches = new TermMatcher().match(query, snapshot, checkpoint);
        Map<Integer, TermSnapshot.Meaning> selected = verifySelections(ordered, matches, checkpoint);
        var applied = new ArrayList<Applied>();
        var suggestions = new ArrayList<SearchQueryPlan.Suggestion>();
        Set<String> present = new LinkedHashSet<>();
        var effective = new StringBuilder(original.length());
        int previous = 0;
        for (TermMatcher.Match match : matches) {
            checkpoint.run();
            for (TermSnapshot.Meaning meaning : match.meanings()) {
                checkpoint.run();
                checkMeaning(meaning);
            }
            TermSnapshot.Meaning adopted = selected.get(match.start());
            boolean automatic = !match.correction() && match.meanings().size() == 1;
            if (adopted == null && automatic) {
                adopted = match.meanings().getFirst();
            }
            effective.append(query.sliceOriginal(previous, match.start()));
            if (selected.containsKey(match.start())) {
                effective.append(adopted.canonicalName());
                present.add(adopted.canonicalKey());
            } else {
                effective.append(query.sliceOriginal(match.start(), match.end()));
                present.add(match.key());
            }
            previous = match.end();
            if (adopted != null) {
                applied.add(new Applied(match, adopted));
            } else {
                SearchQueryPlan.SelectionKind kind = kind(match);
                var options = match.meanings().stream().map(meaning -> new SearchQueryPlan.Option(meaning.termId(), meaning.version(),
                        meaning.canonicalName(), meaning.description())).toList();
                suggestions.add(new SearchQueryPlan.Suggestion(kind, match.start(), match.end(), match.sourceText(), options));
            }
        }
        effective.append(query.sliceOriginal(previous, query.originalLength()));
        String effectiveQuery = effective.toString();
        checkFinalLength(effectiveQuery, maxCodepoints);
        Map<Long, TermSnapshot.Meaning> groups = new LinkedHashMap<>();
        for (Applied item : applied) {
            checkpoint.run();
            TermSnapshot.Meaning existing = groups.putIfAbsent(item.meaning().termId(), item.meaning());
            if (existing != null && !existing.equals(item.meaning())) {
                throw unavailable();
            }
        }
        Map<Long, List<String>> expressions = new LinkedHashMap<>();
        Map<String, String> introduced = new LinkedHashMap<>();
        Map<String, List<Long>> ownership = new HashMap<>();
        int added = 0;
        for (TermSnapshot.Meaning group : groups.values()) {
            checkpoint.run();
            List<String> additions = additions(group, snapshot, present, ownership, checkpoint);
            expressions.put(group.termId(), additions);
            for (String text : additions) {
                checkpoint.run();
                if (introduced.putIfAbsent(TermInput.key(text), text) == null) {
                    added += 1 + text.codePointCount(0, text.length());
                    if (introduced.size() > EXPRESSION_LIMIT || added > ADDED_CODEPOINT_LIMIT) {
                        throw limit();
                    }
                }
            }
        }
        List<SearchQueryPlan.AppliedTerm> appliedTerms = applied.stream().map(item -> new SearchQueryPlan.AppliedTerm(
                item.match().start(), item.match().end(), item.match().sourceText(), item.meaning().termId(), item.meaning().version(),
                item.meaning().canonicalName(), expressions.get(item.meaning().termId()).stream()
                .map(expression -> introduced.get(TermInput.key(expression))).toList())).toList();
        var execution = new StringBuilder(effectiveQuery);
        for (String expression : introduced.values()) {
            checkpoint.run();
            execution.append('\n').append(expression);
        }
        String executionText = execution.toString();
        checkFinalLength(executionText, maxCodepoints);
        checkpoint.run();
        return new SearchQueryPlan(mode, original, effectiveQuery, executionText, state.revision(), appliedTerms, suggestions, ordered);
    }

    private static List<SearchQueryPlan.Selection> structuralSelections(List<SearchQueryPlan.Selection> selections, int length,
                                                                         Runnable checkpoint) {
        if (selections.size() > SELECTION_LIMIT) {
            throw limit();
        }
        for (SearchQueryPlan.Selection selection : selections) {
            checkpoint.run();
            if (selection == null || selection.kind() == null || selection.termId() <= 0 || selection.start() < 0
                    || selection.end() <= selection.start() || selection.end() > length) {
                throw invalid();
            }
        }
        List<SearchQueryPlan.Selection> ordered = selections.stream().sorted(Comparator.comparingInt(SearchQueryPlan.Selection::start)
                .thenComparingInt(SearchQueryPlan.Selection::end)).toList();
        int end = 0;
        for (SearchQueryPlan.Selection selection : ordered) {
            checkpoint.run();
            if (selection.start() < end) {
                throw invalid();
            }
            end = selection.end();
        }
        return ordered;
    }

    private static Map<Integer, TermSnapshot.Meaning> verifySelections(List<SearchQueryPlan.Selection> selections,
                                                                      List<TermMatcher.Match> matches, Runnable checkpoint) {
        var selected = new LinkedHashMap<Integer, TermSnapshot.Meaning>();
        for (SearchQueryPlan.Selection selection : selections) {
            checkpoint.run();
            TermMatcher.Match match = matches.stream().filter(candidate -> candidate.start() == selection.start()
                    && candidate.end() == selection.end()).findFirst().orElseThrow(SearchQueryPlanner::invalid);
            if ((!match.correction() && match.meanings().size() == 1) || kind(match) != selection.kind()) {
                throw invalid();
            }
            TermSnapshot.Meaning target = match.meanings().stream().filter(meaning -> meaning.termId() == selection.termId())
                    .findFirst().orElseThrow(SearchQueryPlanner::invalid);
            selected.put(selection.start(), target);
        }
        return selected;
    }

    private static SearchQueryPlan.SelectionKind kind(TermMatcher.Match match) {
        return match.correction() ? SearchQueryPlan.SelectionKind.CORRECTION : SearchQueryPlan.SelectionKind.DISAMBIGUATION;
    }

    private static List<String> additions(TermSnapshot.Meaning group, TermSnapshot snapshot, Set<String> present,
                                          Map<String, List<Long>> ownership, Runnable checkpoint) {
        List<TermRows.Alias> aliases = snapshot.aliases(group.termId());
        if (aliases == null || aliases.size() > TermInput.ALIAS_LIMIT) {
            throw unavailable();
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.add(group.canonicalKey());
        for (TermRows.Alias alias : aliases) {
            checkpoint.run();
            if (alias == null || alias.termId() != group.termId() || alias.kind() == null || !validExpression(alias.aliasText())
                    || !TermInput.key(alias.aliasText()).equals(alias.lookupKey()) || !keys.add(alias.lookupKey())) {
                throw unavailable();
            }
        }
        var expressions = new ArrayList<String>();
        if (!present.contains(group.canonicalKey())) {
            expressions.add(group.canonicalName());
        }
        for (TermRows.Alias alias : aliases.stream().sorted(Comparator.comparing(TermRows.Alias::lookupKey)).toList()) {
            checkpoint.run();
            if (present.contains(alias.lookupKey())) {
                continue;
            }
            List<Long> owners = ownership.get(alias.lookupKey());
            if (owners == null) {
                owners = snapshot.owners(alias.lookupKey());
                if (owners == null || owners.isEmpty() || owners.size() > 2) {
                    throw unavailable();
                }
                long previous = 0;
                for (Long owner : owners) {
                    checkpoint.run();
                    if (owner == null || owner <= previous) {
                        throw unavailable();
                    }
                    previous = owner;
                }
                owners = List.copyOf(owners);
                ownership.put(alias.lookupKey(), owners);
            }
            if (owners.size() == 1) {
                if (owners.getFirst() != group.termId()) {
                    throw unavailable();
                }
                expressions.add(alias.aliasText());
            }
        }
        return List.copyOf(expressions);
    }

    private static boolean validExpression(String expression) {
        try {
            return expression != null && expression.equals(TermInput.expression(expression));
        } catch (TermException invalid) {
            return false;
        }
    }

    private static void checkMeaning(TermSnapshot.Meaning meaning) {
        try {
            if (meaning.termId() <= 0 || meaning.version() < 0 || !validExpression(meaning.canonicalName())
                    || !TermInput.key(meaning.canonicalName()).equals(meaning.canonicalKey()) || meaning.description() == null
                    || !meaning.description().equals(TermInput.text(meaning.description(), TermInput.DESCRIPTION_LIMIT, false))) {
                throw unavailable();
            }
        } catch (TermException invalid) {
            throw unavailable();
        }
    }

    private static void checkFinalLength(String text, int maxCodepoints) {
        if (text.codePointCount(0, text.length()) > maxCodepoints) {
            throw limit();
        }
    }

    private static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
    }

    private static SearchFailure unavailable() {
        return new SearchFailure(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE);
    }

    private static SearchFailure limit() {
        return new SearchFailure(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
    }
}
