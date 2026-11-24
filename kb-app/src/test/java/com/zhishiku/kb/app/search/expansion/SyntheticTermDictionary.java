package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.term.TermInput;
import com.zhishiku.kb.app.search.term.TermRows;
import com.zhishiku.kb.app.search.term.TermSnapshot;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 固定合成关系的内存替身，仅用于独立规则单测，不代表真实业务词典。 */
public class SyntheticTermDictionary implements TermSnapshot {
    private record Group(Meaning meaning, List<TermRows.Alias> aliases, boolean enabled) {
    }

    private record Correction(long target, boolean enabled) {
    }

    record Alias(String text, TermRows.AliasKind kind) {
    }

    final Map<Long, Integer> aliasReads = new LinkedHashMap<>();
    final Map<String, Integer> ownerReads = new LinkedHashMap<>();
    private final Map<Long, Group> groups = new LinkedHashMap<>();
    private final Map<String, Correction> corrections = new LinkedHashMap<>();
    int probeReads;
    int relationReads;
    long revision = 7;

    SyntheticTermDictionary group(long id, String name, String description, Alias... aliases) {
        String text = TermInput.expression(name);
        var meaning = new Meaning(id, text, TermInput.key(text), TermInput.text(description, 256, false), Long.MAX_VALUE);
        var related = Arrays.stream(aliases).map(alias -> {
            String value = TermInput.expression(alias.text());
            return new TermRows.Alias(id, value, TermInput.key(value), alias.kind());
        }).toList();
        groups.put(id, new Group(meaning, related, true));
        return this;
    }

    SyntheticTermDictionary disabled(long id) {
        var group = groups.get(id);
        groups.put(id, new Group(group.meaning(), group.aliases(), false));
        return this;
    }

    SyntheticTermDictionary correction(String wrong, long target) {
        corrections.put(TermInput.key(TermInput.expression(wrong)), new Correction(target, true));
        return this;
    }

    SyntheticTermDictionary disabledCorrection(String wrong) {
        var key = TermInput.key(TermInput.expression(wrong));
        var correction = corrections.get(key);
        corrections.put(key, new Correction(correction.target(), false));
        return this;
    }

    @Override
    public TermRows.State state() {
        return new TermRows.State(revision, OffsetDateTime.parse("2026-11-24T00:00:00Z"));
    }

    @Override
    public Set<String> probe(List<String> keys) {
        probeReads++;
        return keys.stream().filter(key -> !related(key).terms().isEmpty() || related(key).correction() != null).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Relations relations(String key) {
        relationReads++;
        return related(key);
    }

    private Relations related(String key) {
        List<Meaning> meanings = groups.values().stream().filter(group -> group.enabled() && owns(group, key)).map(Group::meaning)
                .sorted(Comparator.comparingLong(Meaning::termId)).limit(9).toList();
        var correction = corrections.get(key);
        var target = correction == null ? null : groups.get(correction.target());
        return new Relations(meanings, correction != null && correction.enabled() && target != null && target.enabled() ? target.meaning() : null);
    }

    @Override
    public List<TermRows.Alias> aliases(long termId) {
        aliasReads.merge(termId, 1, Integer::sum);
        return groups.get(termId).aliases();
    }

    @Override
    public List<Long> owners(String key) {
        ownerReads.merge(key, 1, Integer::sum);
        return groups.values().stream().filter(group -> group.enabled() && owns(group, key)).map(group -> group.meaning().termId())
                .sorted().limit(2).toList();
    }

    private static boolean owns(Group group, String key) {
        return group.meaning().canonicalKey().equals(key) || group.aliases().stream().anyMatch(alias -> alias.lookupKey().equals(key));
    }

    static Alias synonym(String text) {
        return new Alias(text, TermRows.AliasKind.SYNONYM);
    }

    static Alias abbreviation(String text) {
        return new Alias(text, TermRows.AliasKind.ABBREVIATION);
    }

    static Alias former(String text) {
        return new Alias(text, TermRows.AliasKind.FORMER_NAME);
    }

    public static SyntheticTermDictionary examples() {
        return new SyntheticTermDictionary()
                .group(1, "办公自动化", "办公流程与系统", abbreviation("OA"), former("旧办公系统"))
                .group(2, "报销", "费用报销", synonym("费用报销"))
                .group(3, "项目管理", "项目执行与协调", abbreviation("PM"), synonym("项目协调"))
                .group(4, "产品经理", "产品规划岗位", abbreviation("PM"))
                .correction("报消", 2);
    }
}
