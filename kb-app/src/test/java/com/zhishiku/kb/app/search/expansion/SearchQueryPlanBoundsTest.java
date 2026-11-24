package com.zhishiku.kb.app.search.expansion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.search.assistance.SearchSelectionContext;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.app.search.term.TermSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.CORRECTION;
import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.DISAMBIGUATION;
import static com.zhishiku.kb.app.search.expansion.SearchQueryPlanTest.plan;
import static com.zhishiku.kb.app.search.expansion.SyntheticTermDictionary.abbreviation;
import static com.zhishiku.kb.app.search.expansion.SyntheticTermDictionary.synonym;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 只核对计划、投影和传递给消费者替身的文本，不调用实际三个检索阶段。 */
class SearchQueryPlanBoundsTest {
    @Test
    void thirtyTwoExpressionsPassAndThirtyThirdFailsInsteadOfBeingDropped() {
        var full = plan("甲组 乙组", expressions(32, false, false));
        assertThat(additions(full)).hasSize(32);
        assertThat(full.executionText()).isEqualTo(full.effectiveQuery() + "\n" + String.join("\n", additions(full)));
        limit(() -> plan("甲组 乙组", expressions(33, false, false)));
    }

    @Test
    void supplementaryCodepointsAndEveryNewlineCountToward2048And2049Limit() {
        var exact = plan("甲组 乙组", expressions(32, true, false));
        assertThat(exact.executionText().codePointCount(0, exact.executionText().length()) - exact.effectiveQuery().codePointCount(0, exact.effectiveQuery().length()))
                .isEqualTo(2048);
        assertThat(exact.executionText().length()).isGreaterThan(exact.executionText().codePointCount(0, exact.executionText().length()));
        limit(() -> plan("甲组 乙组", expressions(32, true, true)));
    }

    @Test
    void finalTextAt8000PassesAnd8001FailsWhileOriginalCanStillUseItsWholeValidInput() {
        var dictionary = new SyntheticTermDictionary().group(1, "甲组", "释义", synonym("x"));
        var exact = plan("甲组" + "空".repeat(7996), dictionary);
        assertThat(exact.executionText().codePointCount(0, exact.executionText().length())).isEqualTo(8000);
        String original = "甲组" + "空".repeat(7997);
        limit(() -> plan(original, dictionary));
        var fallback = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ORIGINAL, original, 8000, List.of(), null,
                SearchQueryPlanTest::checkpoint);
        assertThat(fallback.executionText()).isEqualTo(original);
    }

    @Test
    void longerExplicitReplacementIsAlsoCheckedBeforeAnyExpansionRead() {
        var dictionary = new SyntheticTermDictionary().group(1, "正确", "释义").correction("错", 1);
        String original = "错" + "空".repeat(7999);
        limit(() -> plan(original, dictionary, new SearchQueryPlan.Selection(CORRECTION, 0, 1, 1)));
        assertThat(dictionary.aliasReads).isEmpty();
        var configured = new SyntheticTermDictionary().group(1, "甲组", "释义", synonym("补充"));
        limit(() -> new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, "甲组", 4, List.of(), configured,
                SearchQueryPlanTest::checkpoint));
    }

    @Test
    void allExpressionsAlreadyInOriginalAppearOnlyInPositionFactsAndAreNeverAppendedAgain() {
        var dictionary = SyntheticTermDictionary.examples();
        var plan = plan("OA 办公自动化 OA", dictionary);
        assertThat(plan.appliedTerms()).hasSize(3);
        assertThat(plan.appliedTerms()).allSatisfy(item -> assertThat(item.expressions()).containsExactly("旧办公系统"));
        assertThat(plan.executionText()).isEqualTo("OA 办公自动化 OA\n旧办公系统");
        assertThat(dictionary.aliasReads).containsEntry(1L, 1);
    }

    @Test
    void aliasInputOrderDoesNotChangeStableOrderAndPositionTakesPriorityOverGroupId() {
        var one = new SyntheticTermDictionary().group(1, "甲组", "释义", synonym("z"), synonym("a"))
                .group(Long.MAX_VALUE, "乙组", "释义", synonym("y"), synonym("b"));
        var two = new SyntheticTermDictionary().group(1, "甲组", "释义", synonym("a"), synonym("z"))
                .group(Long.MAX_VALUE, "乙组", "释义", synonym("b"), synonym("y"));
        var first = plan("乙组 甲组", one);
        assertThat(first).isEqualTo(plan("乙组 甲组", two));
        assertThat(first.executionText()).isEqualTo("乙组 甲组\nb\ny\na\nz");
        assertThat(first.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::termId).containsExactly(Long.MAX_VALUE, 1L);
    }

    @Test
    void identityAssistedPlanKeepsZeroRevisionAndOriginalModeDoesNotAccessEvenBrokenSnapshot() {
        String original = " 😀未登记 %_ ";
        var empty = new SyntheticTermDictionary();
        empty.revision = 0;
        var identity = plan(original, empty);
        assertThat(identity.originalQuery()).isEqualTo(original);
        assertThat(identity.effectiveQuery()).isEqualTo(original);
        assertThat(identity.executionText()).isEqualTo(original);
        assertThat(identity.dictionaryRevision()).isZero();
        assertThat(identity.appliedTerms()).isEmpty();
        assertThat(identity.suggestions()).isEmpty();
        var snapshot = mock(TermSnapshot.class);
        var bypass = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ORIGINAL, "OA ".repeat(9), 8000, List.of(), snapshot,
                SearchQueryPlanTest::checkpoint);
        assertThat(bypass.effectiveQuery()).isEqualTo(bypass.originalQuery());
        assertThat(bypass.executionText()).isEqualTo(bypass.originalQuery());
        assertThat(bypass.dictionaryRevision()).isNull();
        assertThat(bypass.appliedTerms()).isEmpty();
        assertThat(bypass.suggestions()).isEmpty();
        assertThat(bypass.selections()).isEmpty();
        verifyNoInteractions(snapshot);
        assertThatThrownBy(() -> new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ORIGINAL, "报消", 8000,
                List.of(new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2)), snapshot, SearchQueryPlanTest::checkpoint))
                .isInstanceOfSatisfying(SearchFailure.class, failure -> assertThat(failure.kind()).isEqualTo(SearchFailure.Kind.INVALID_INPUT));
        verifyNoInteractions(snapshot);
    }

    @Test
    void explanationUnionReconstructsTheOneExecutionTextForEveryConsumerStub() {
        var plan = plan("OA 报消", SyntheticTermDictionary.examples(), new SearchQueryPlan.Selection(CORRECTION, 3, 5, 2));
        String projected = plan.assistance(null).effectiveQuery() + "\n" + String.join("\n", additions(plan));
        assertThat(projected).isEqualTo(plan.executionText());
        var keyword = mock(Consumer.class);
        var embedding = mock(Consumer.class);
        var rerank = mock(Consumer.class);
        keyword.accept(plan.executionText());
        embedding.accept(plan.executionText());
        rerank.accept(plan.executionText());
        verify(keyword).accept(projected);
        verify(embedding).accept(projected);
        verify(rerank).accept(projected);
    }

    @Test
    void assistanceProjectionHasExactIdsRequiredNullsAndNoExecutionOrInternalLookupFields() {
        var dictionary = new SyntheticTermDictionary().group(Long.MAX_VALUE, "项目管理", "项目释义", abbreviation("PM"))
                .group(7, "产品经理", "岗位释义", abbreviation("PM"));
        dictionary.revision = Long.MAX_VALUE;
        var planned = plan("PM", dictionary);
        var json = new ObjectMapper().valueToTree(planned.assistance("context-probe"));
        assertThat(json.size()).isEqualTo(8);
        assertThat(json.get("dictionaryRevision").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.get("suggestions").get(0).get("options").get(1).get("termId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.get("suggestions").get(0).get("options").get(1).get("termVersion").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(json.has("executionText")).isFalse();
        assertThat(json.has("canonicalKey")).isFalse();
        var selected = plan("PM", dictionary, new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, Long.MAX_VALUE));
        var adopted = new ObjectMapper().valueToTree(selected.assistance(null));
        assertThat(adopted.get("selections").get(0).get("termId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(adopted.get("appliedTerms").get(0).get("termId").textValue()).isEqualTo(Long.toString(Long.MAX_VALUE));
        assertThat(adopted.get("selectionContext").isNull()).isTrue();
        var original = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ORIGINAL, "PM", 8000, List.of(), null, SearchQueryPlanTest::checkpoint);
        var originalJson = new ObjectMapper().valueToTree(original.assistance(null));
        assertThat(originalJson.size()).isEqualTo(8);
        assertThat(originalJson.get("dictionaryRevision").isNull()).isTrue();
        assertThat(originalJson.get("selectionContext").isNull()).isTrue();
    }

    @Test
    void outputListsAreImmutableAndDiagnosticsDoNotRetainQueryOrCredentialBindings() {
        var plan = plan("OA PM", SyntheticTermDictionary.examples());
        var projection = plan.assistance("credential-probe");
        assertThatThrownBy(() -> plan.appliedTerms().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.appliedTerms().getFirst().expressions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.suggestions().getFirst().options().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.selections().add(new SearchQueryPlan.Selection(DISAMBIGUATION, 3, 5, 3)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> projection.suggestions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(plan.toString()).doesNotContain("OA PM");
        assertThat(projection.toString()).doesNotContain("OA PM", "credential-probe");
        assertThat(plan.appliedTerms().getFirst().toString()).doesNotContain("OA", "办公自动化");
        assertThat(plan.suggestions().getFirst().toString()).doesNotContain("PM");
        assertThat(Arrays.stream(SearchQueryPlan.class.getRecordComponents()).map(component -> component.getType().getName()).toList())
                .doesNotContain(TermSnapshot.class.getName(), SearchSelectionContext.Binding.class.getName(), Runnable.class.getName());
    }

    @Test
    void missingContextAndInvalidOriginalOrRequiredFieldCannotProducePartialAssistance() {
        var pending = plan("PM", SyntheticTermDictionary.examples());
        assertThatThrownBy(() -> pending.assistance(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.assistance("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pending.assistance("x".repeat(4097))).isInstanceOf(IllegalArgumentException.class);
        assertThat(pending.assistance("x".repeat(4096)).selectionContext()).hasSize(4096);
        var complete = plan("OA", SyntheticTermDictionary.examples());
        assertThatThrownBy(() -> complete.assistance("unexpected")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQueryPlan(null, "a", "a", "a", null, List.of(), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SearchQueryPlan(SearchQueryPlan.Mode.ASSISTED, "a", "a", "a", null, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchQueryPlan(SearchQueryPlan.Mode.ORIGINAL, "a", "b", "b", null, List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corruptAliasesOrOwnerFactsAreUnavailableNotSilentLossOfAnExpectedExpansion() {
        var noOwners = new SyntheticTermDictionary() {
            @Override
            public List<Long> owners(String key) {
                return List.of();
            }
        }.group(1, "甲组", "释义", synonym("x"));
        unavailable(() -> plan("甲组", noOwners));
        var wrongOwner = new SyntheticTermDictionary() {
            @Override
            public List<Long> owners(String key) {
                return List.of(9L);
            }
        }.group(1, "甲组", "释义", synonym("x"));
        unavailable(() -> plan("甲组", wrongOwner));
        var tooMany = new SyntheticTermDictionary().group(1, "甲组", "释义", IntStream.range(0, 21)
                .mapToObj(index -> synonym("x" + index)).toArray(SyntheticTermDictionary.Alias[]::new));
        unavailable(() -> plan("甲组", tooMany));
        var duplicate = new SyntheticTermDictionary().group(1, "甲组", "释义", synonym("x"), synonym("X"));
        unavailable(() -> plan("甲组", duplicate));
    }

    @Test
    void eightSelectionsPassWhileNinthIsRejectedBeforeProbeAndCancellationNeverDeliversSuccess() {
        var choices = IntStream.range(0, 8).mapToObj(index -> new SearchQueryPlan.Selection(CORRECTION, index * 3, index * 3 + 2, 2)).toList();
        var eight = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, "报消 ".repeat(8).stripTrailing(), 8000, choices,
                SyntheticTermDictionary.examples(), SearchQueryPlanTest::checkpoint);
        assertThat(eight.selections()).hasSize(8);
        assertThat(eight.suggestions()).isEmpty();
        var dictionary = SyntheticTermDictionary.examples();
        var nine = new ArrayList<>(choices);
        nine.add(new SearchQueryPlan.Selection(CORRECTION, 24, 26, 2));
        limit(() -> new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, "报消 ".repeat(9).stripTrailing(), 8000, nine,
                dictionary, SearchQueryPlanTest::checkpoint));
        assertThat(dictionary.probeReads).isZero();
        var cancelled = new AtomicBoolean();
        var duringExpansion = new SyntheticTermDictionary() {
            @Override
            public List<Long> owners(String key) {
                cancelled.set(true);
                return super.owners(key);
            }
        }.group(1, "甲组", "释义", synonym("x"));
        var failure = new SearchFailure(SearchFailure.Kind.CANCELLED);
        assertThatThrownBy(() -> new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, "甲组", 8000, List.of(), duringExpansion, () -> {
            if (cancelled.get()) {
                throw failure;
            }
        })).isSameAs(failure);
    }

    private static SyntheticTermDictionary expressions(int count, boolean supplementary, boolean oneMore) {
        var aliases = IntStream.range(0, count).mapToObj(index -> {
            String prefix = "a" + (index < 10 ? "0" : "") + index;
            String fill = supplementary ? "😀" : "字";
            return synonym(prefix + fill.repeat(oneMore && index == count - 1 ? 61 : 60));
        }).toList();
        return new SyntheticTermDictionary().group(1, "甲组", "释义", aliases.subList(0, 16).toArray(SyntheticTermDictionary.Alias[]::new))
                .group(2, "乙组", "释义", aliases.subList(16, count).toArray(SyntheticTermDictionary.Alias[]::new));
    }

    private static List<String> additions(SearchQueryPlan plan) {
        var added = new LinkedHashSet<String>();
        plan.appliedTerms().forEach(item -> added.addAll(item.expressions()));
        return List.copyOf(added);
    }

    private static void limit(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_LIMIT_EXCEEDED));
    }

    private static void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(SearchFailure.Kind.ASSISTANCE_UNAVAILABLE));
    }
}
