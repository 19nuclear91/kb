package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.term.TermInput;
import com.zhishiku.kb.app.search.term.TermSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 合成关系存储作为下游替身，规则组件不读取真实词典或外部检索。 */
class TermMatcherTest {
    @Test
    void earliestStartWinsAndLongestAtThatStartExcludesEveryOverlappingShorterRelation() {
        var f = new Fixture();
        f.group(1, "报销", true);
        f.group(2, "差旅报销", true);
        f.group(3, "差旅", true);
        var matches = f.match("差旅报销流程");
        assertThat(matches).hasSize(1);
        assertThat(matches.getFirst().key()).isEqualTo("差旅报销");
        assertThat(matches.getFirst().start()).isZero();
        assertThat(matches.getFirst().end()).isEqualTo(4);
        assertThat(matches.getFirst().meanings()).extracting(TermSnapshot.Meaning::termId).containsExactly(2L);
        verify(f.snapshot, never()).relations("报销");
        verify(f.snapshot, never()).relations("差旅");
        var left = new Fixture();
        left.group(1, "项目", true);
        left.group(2, "目管理", true);
        assertThat(left.match("项目管理")).extracting(TermMatcher.Match::key).containsExactly("项目");
    }

    @Test
    void sharedAliasesAndCanonicalAliasCollisionRetainAllEnabledMeaningsInIdentityOrder() {
        var f = new Fixture();
        f.group(Long.MAX_VALUE, "产品经理", true, "PM");
        f.group(1, "PM", true);
        var match = f.match("PM使用说明").getFirst();
        assertThat(match.correction()).isFalse();
        assertThat(match.meanings()).extracting(TermSnapshot.Meaning::termId).containsExactly(1L, Long.MAX_VALUE);
        assertThat(match.sourceText()).isEqualTo("PM");
        verify(f.snapshot, never()).aliases(anyLong());
        verify(f.snapshot, never()).owners(anyString());
    }

    @Test
    void disabledGroupsDoNotContributeToProbeOrAmbiguityAndNoCreationOrderPreferenceIsIntroduced() {
        var f = new Fixture();
        var first = f.group(2, "项目管理", true, "PM");
        var second = f.group(1, "产品经理", false, "PM");
        assertThat(f.match("PM").getFirst().meanings()).extracting(TermSnapshot.Meaning::termId).containsExactly(2L);
        second.enabled().set(true);
        assertThat(f.match("PM").getFirst().meanings()).extracting(TermSnapshot.Meaning::termId).containsExactly(1L, 2L);
        first.enabled().set(false);
        second.enabled().set(false);
        assertThat(f.match("PM")).isEmpty();
    }

    @Test
    void eightRepeatedPositionsRemainIndependentButNinthRejectsBeforeLoadingItsRelations() {
        var f = new Fixture();
        f.group(1, "办公自动化", true, "OA");
        var eight = f.match("OA ".repeat(8).stripTrailing());
        assertThat(eight).hasSize(8);
        assertThat(eight).extracting(TermMatcher.Match::start).containsExactly(0, 3, 6, 9, 12, 15, 18, 21);
        verify(f.snapshot, times(8)).relations("oa");
        var nine = new Fixture();
        nine.group(1, "办公自动化", true, "OA");
        limit(() -> nine.match("OA ".repeat(9).stripTrailing()));
        verify(nine.snapshot, times(8)).relations("oa");
    }

    @Test
    void eightSharedMeaningsAreCompleteAndNinthIsLimitEvidenceNotTruncatedSuccess() {
        var eight = new Fixture();
        for (int id = 1; id <= 8; id++) {
            eight.group(id, "含义" + id, true, "PM");
        }
        assertThat(eight.match("PM").getFirst().meanings()).hasSize(8);
        var nine = new Fixture();
        for (int id = 1; id <= 10; id++) {
            nine.group(id, "含义" + id, true, "PM");
        }
        limit(() -> nine.match("PM"));
        verify(nine.snapshot).relations("pm");
        verify(nine.snapshot, never()).aliases(anyLong());
    }

    @Test
    void crossBatchLongestWordCoversNextBatchAndAvoidsAnotherDatabaseProbeForCoveredStarts() {
        String text = IntStream.range(0, 191).collect(StringBuilder::new,
                (builder, index) -> builder.appendCodePoint(0x4E00 + index), StringBuilder::append).toString();
        String longWord = text.substring(127, 191);
        var f = new Fixture();
        f.group(1, longWord, true);
        var match = f.match(text).getFirst();
        assertThat(match.start()).isEqualTo(127);
        assertThat(match.end()).isEqualTo(191);
        assertThat(match.sourceText()).isEqualTo(longWord);
        verify(f.snapshot).probe(any());
        verify(f.snapshot).relations(longWord);
    }

    @Test
    void correctionsAreSingleDirectionalAndNfcCoordinatesAlwaysReferToUnchangedOriginalQuery() {
        var f = new Fixture();
        f.group(1, "报销", true);
        f.corrections.add(new Correction("报消", 1));
        var match = f.match("😀报消流程").getFirst();
        assertThat(match.correction()).isTrue();
        assertThat(match.start()).isEqualTo(1);
        assertThat(match.end()).isEqualTo(3);
        assertThat(match.sourceText()).isEqualTo("报消");
        assertThat(match.meanings()).extracting(TermSnapshot.Meaning::canonicalName).containsExactly("报销");
        var normalized = new Fixture();
        normalized.group(1, "À\u0315", true);
        var composed = normalized.match("😀A\u0315\u0300流程").getFirst();
        assertThat(composed.start()).isEqualTo(1);
        assertThat(composed.end()).isEqualTo(4);
        assertThat(composed.sourceText()).isEqualTo("A\u0315\u0300");
        assertThat(composed.toString()).doesNotContain("流程", "A\u0315\u0300");
    }

    @Test
    void corruptRelationsAndProbeDisagreementFailWithoutPretendingToBeNoMatchOrUnique() {
        var f = new Fixture();
        f.group(1, "PM", true);
        when(f.snapshot.relations("pm")).thenReturn(new TermSnapshot.Relations(List.of(), null));
        unavailable(() -> f.match("PM"));
        var mixed = new Fixture();
        mixed.group(1, "PM", true);
        mixed.group(2, "目标", true);
        mixed.corrections.add(new Correction("pm", 2));
        unavailable(() -> mixed.match("PM"));
        var foreign = new Fixture();
        doReturn(Set.of("foreign-key")).when(foreign.snapshot).probe(any());
        unavailable(() -> foreign.match("PM"));
        var missing = new Fixture();
        missing.group(1, "PM", true);
        when(missing.snapshot.relations("pm")).thenReturn(null);
        unavailable(() -> missing.match("PM"));
    }

    @Test
    void noMatchAndCancellationDoNotStartRelationOrAliasWork() {
        var empty = new Fixture();
        assertThat(empty.match("未登记近似表达")).isEmpty();
        verify(empty.snapshot, never()).relations(anyString());
        verify(empty.snapshot, never()).aliases(anyLong());
        var f = new Fixture();
        f.group(1, "OA", true);
        var cancelled = new AtomicBoolean();
        doAnswer(invocation -> {
            cancelled.set(true);
            return Set.of("oa");
        }).when(f.snapshot).probe(any());
        var failure = new SearchFailure(SearchFailure.Kind.CANCELLED);
        assertThatThrownBy(() -> new TermMatcher().match(query("OA"), f.snapshot, () -> {
            if (cancelled.get()) {
                throw failure;
            }
        })).isSameAs(failure);
        verify(f.snapshot, never()).relations(anyString());
    }

    private static void limit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class, error -> {
            assertThat(error.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED);
            assertThat(error.status()).isEqualTo(400);
        });
    }

    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class,
                error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE));
    }

    private static NormalizedQuery query(String text) {
        return NormalizedQuery.create(text, 8000, TermMatcherTest::checkpoint);
    }

    private static void checkpoint() {
    }

    private record Registered(TermSnapshot.Meaning meaning, Set<String> keys, AtomicBoolean enabled) {
    }

    private record Correction(String key, long target) {
    }

    private static final class Fixture {
        private final TermSnapshot snapshot = mock(TermSnapshot.class);
        private final List<Registered> groups = new ArrayList<>();
        private final List<Correction> corrections = new ArrayList<>();

        private Fixture() {
            when(snapshot.probe(any())).thenAnswer(invocation -> {
                var keys = new LinkedHashSet<String>();
                for (String key : invocation.<List<String>>getArgument(0)) {
                    var related = relations(key);
                    if (!related.terms().isEmpty() || related.correction() != null) {
                        keys.add(key);
                    }
                }
                return Set.copyOf(keys);
            });
            when(snapshot.relations(anyString())).thenAnswer(invocation -> relations(invocation.getArgument(0)));
        }

        private Registered group(long id, String name, boolean enabled, String... aliases) {
            String normalized = TermInput.expression(name);
            var meaning = new TermSnapshot.Meaning(id, normalized, TermInput.key(normalized), "释义", Long.MAX_VALUE);
            var keys = new LinkedHashSet<String>();
            keys.add(meaning.canonicalKey());
            for (String alias : aliases) {
                keys.add(TermInput.key(TermInput.expression(alias)));
            }
            var group = new Registered(meaning, keys, new AtomicBoolean(enabled));
            groups.add(group);
            return group;
        }

        private TermSnapshot.Relations relations(String key) {
            var terms = groups.stream().filter(group -> group.enabled().get() && group.keys().contains(key)).map(Registered::meaning)
                    .sorted(Comparator.comparingLong(TermSnapshot.Meaning::termId)).limit(9).toList();
            TermSnapshot.Meaning correction = corrections.stream().filter(mapping -> mapping.key().equals(key))
                    .flatMap(mapping -> groups.stream().filter(group -> group.enabled().get() && group.meaning().termId() == mapping.target()))
                    .map(Registered::meaning).findFirst().orElse(null);
            return new TermSnapshot.Relations(terms, correction);
        }

        private List<TermMatcher.Match> match(String text) {
            return new TermMatcher().match(query(text), snapshot, TermMatcherTest::checkpoint);
        }
    }
}
