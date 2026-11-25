package com.zhishiku.kb.app.search.expansion;

import com.zhishiku.kb.app.search.model.SearchFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.CORRECTION;
import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.DISAMBIGUATION;
import static com.zhishiku.kb.app.search.expansion.SyntheticTermDictionary.abbreviation;
import static com.zhishiku.kb.app.search.expansion.SyntheticTermDictionary.synonym;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryPlanTest {
    @Test
    void abbreviationCanonicalAndFormerNameWorkBothDirectionsWithoutChangingOriginalText() {
        var abbreviation = plan("OA 使用说明", SyntheticTermDictionary.examples());
        assertThat(abbreviation.originalQuery()).isEqualTo("OA 使用说明");
        assertThat(abbreviation.effectiveQuery()).isEqualTo("OA 使用说明");
        assertThat(abbreviation.executionText()).isEqualTo("OA 使用说明\n办公自动化\n旧办公系统");
        assertThat(abbreviation.appliedTerms().getFirst().expressions()).containsExactly("办公自动化", "旧办公系统");
        assertThat(abbreviation.suggestions()).isEmpty();
        var canonical = plan("办公自动化 使用说明", SyntheticTermDictionary.examples());
        assertThat(canonical.executionText()).isEqualTo("办公自动化 使用说明\nOA\n旧办公系统");
        var former = plan("旧办公系统 使用说明", SyntheticTermDictionary.examples());
        assertThat(former.executionText()).isEqualTo("旧办公系统 使用说明\n办公自动化\nOA");
    }

    @Test
    void sharedAliasWaitsForChoiceAndUniqueCanonicalDoesNotExpandThroughIt() {
        var dictionary = SyntheticTermDictionary.examples();
        var ambiguous = plan("PM", dictionary);
        assertThat(ambiguous.effectiveQuery()).isEqualTo("PM");
        assertThat(ambiguous.executionText()).isEqualTo("PM");
        assertThat(ambiguous.appliedTerms()).isEmpty();
        assertThat(ambiguous.suggestions()).hasSize(1);
        assertThat(ambiguous.suggestions().getFirst().kind()).isEqualTo(DISAMBIGUATION);
        assertThat(ambiguous.suggestions().getFirst().options()).extracting(SearchQueryPlan.Option::termId).containsExactly(3L, 4L);
        assertThat(dictionary.aliasReads).isEmpty();
        var unique = plan("项目管理", dictionary);
        assertThat(unique.appliedTerms().getFirst().expressions()).containsExactly("项目协调");
        assertThat(unique.executionText()).isEqualTo("项目管理\n项目协调");
        assertThat(dictionary.ownerReads).containsEntry("pm", 1);
        assertThat(dictionary.aliasReads).containsOnlyKeys(3L);
    }

    @Test
    void selectedMeaningOnlyChangesItsOriginalPositionAndNeverAutomaticallyMergesOtherGroups() {
        var selection = new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 3);
        var chosen = plan("PM", SyntheticTermDictionary.examples(), selection);
        assertThat(chosen.originalQuery()).isEqualTo("PM");
        assertThat(chosen.effectiveQuery()).isEqualTo("项目管理");
        assertThat(chosen.executionText()).isEqualTo("项目管理\n项目协调");
        assertThat(chosen.appliedTerms()).hasSize(1);
        assertThat(chosen.appliedTerms().getFirst().matchedText()).isEqualTo("PM");
        assertThat(chosen.appliedTerms().getFirst().termId()).isEqualTo(3);
        assertThat(chosen.suggestions()).isEmpty();
        assertThat(chosen.selections()).containsExactly(selection);
        var other = plan("PM", SyntheticTermDictionary.examples(), new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 4));
        assertThat(other.effectiveQuery()).isEqualTo("产品经理");
        assertThat(other.executionText()).isEqualTo("产品经理");
        assertThat(other.appliedTerms().getFirst().expressions()).isEmpty();
    }

    @Test
    void wrongExpressionRemainsOriginalUntilConfirmedAndNeverBecomesReverseSynonym() {
        var suggested = plan("报消流程", SyntheticTermDictionary.examples());
        assertThat(suggested.executionText()).isEqualTo("报消流程");
        assertThat(suggested.appliedTerms()).isEmpty();
        assertThat(suggested.suggestions().getFirst().kind()).isEqualTo(CORRECTION);
        assertThat(suggested.suggestions().getFirst().sourceText()).isEqualTo("报消");
        var adopted = plan("报消流程", SyntheticTermDictionary.examples(), new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2));
        assertThat(adopted.originalQuery()).isEqualTo("报消流程");
        assertThat(adopted.effectiveQuery()).isEqualTo("报销流程");
        assertThat(adopted.executionText()).isEqualTo("报销流程\n费用报销");
        assertThat(adopted.suggestions()).isEmpty();
        var correct = plan("报销流程", SyntheticTermDictionary.examples());
        assertThat(correct.executionText()).isEqualTo("报销流程\n费用报销");
        assertThat(correct.appliedTerms().getFirst().expressions()).doesNotContain("报消");
    }

    @Test
    void automaticExpansionAndPendingCorrectionHaveSeparateAccurateFacts() {
        var mixed = plan("OA 报消流程", SyntheticTermDictionary.examples());
        assertThat(mixed.effectiveQuery()).isEqualTo("OA 报消流程");
        assertThat(mixed.executionText()).isEqualTo("OA 报消流程\n办公自动化\n旧办公系统");
        assertThat(mixed.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::termId).containsExactly(1L);
        assertThat(mixed.suggestions()).extracting(SearchQueryPlan.Suggestion::kind).containsExactly(CORRECTION);
        var adopted = plan("OA 报消流程", SyntheticTermDictionary.examples(), new SearchQueryPlan.Selection(CORRECTION, 3, 5, 2));
        assertThat(adopted.effectiveQuery()).isEqualTo("OA 报销流程");
        assertThat(adopted.executionText()).isEqualTo("OA 报销流程\n办公自动化\n旧办公系统\n费用报销");
        assertThat(adopted.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::matchedText).containsExactly("OA", "报消");
    }

    @Test
    void cumulativeChoicesUseOriginalCoordinatesAndLeaveOtherSuggestionsUnadopted() {
        String original = "报消 报消 PM";
        var first = new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2);
        var second = new SearchQueryPlan.Selection(DISAMBIGUATION, 6, 8, 3);
        var one = plan(original, SyntheticTermDictionary.examples(), first);
        assertThat(one.effectiveQuery()).isEqualTo("报销 报消 PM");
        assertThat(one.suggestions()).extracting(SearchQueryPlan.Suggestion::start).containsExactly(3, 6);
        var two = plan(original, SyntheticTermDictionary.examples(), second, first);
        assertThat(two.originalQuery()).isEqualTo(original);
        assertThat(two.effectiveQuery()).isEqualTo("报销 报消 项目管理");
        assertThat(two.selections()).containsExactly(first, second);
        assertThat(two.suggestions()).extracting(SearchQueryPlan.Suggestion::start).containsExactly(3);
        assertThat(two.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::matchedText).containsExactly("报消", "PM");
    }

    @Test
    void onlySelectedIntervalsChangeAndUnmatchedCombiningCharactersStayByteForByteOriginal() {
        String prefix = "😀A\u0315\u0300 ";
        String original = prefix + "报消 PM";
        var adopted = plan(original, SyntheticTermDictionary.examples(), new SearchQueryPlan.Selection(DISAMBIGUATION, 8, 10, 3),
                new SearchQueryPlan.Selection(CORRECTION, 5, 7, 2));
        assertThat(adopted.effectiveQuery()).isEqualTo(prefix + "报销 项目管理");
        assertThat(adopted.originalQuery()).isEqualTo(original);
        assertThat(adopted.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::start).containsExactly(5, 8);
    }

    @Test
    void oneIllegalTargetRejectsWholeSelectionSetBeforeReadingExpansionRelations() {
        var dictionary = SyntheticTermDictionary.examples();
        invalid(() -> plan("报消 PM", dictionary, new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2),
                new SearchQueryPlan.Selection(DISAMBIGUATION, 3, 5, 1)));
        assertThat(dictionary.aliasReads).isEmpty();
        assertThat(dictionary.ownerReads).isEmpty();
        invalid(() -> plan("OA", dictionary, new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 1)));
        invalid(() -> plan("报消", dictionary, new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 2)));
        invalid(() -> plan("报消", dictionary, new SearchQueryPlan.Selection(CORRECTION, 0, 1, 2)));
        assertThat(dictionary.aliasReads).isEmpty();
    }

    @Test
    void duplicateOverlappingInvalidKindsAndOutOfRangeCoordinatesFailBeforeDictionaryProbe() {
        var dictionary = SyntheticTermDictionary.examples();
        var first = new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2);
        invalid(() -> plan("报消", dictionary, first, first));
        invalid(() -> plan("报消", dictionary, first, new SearchQueryPlan.Selection(CORRECTION, 1, 2, 2)));
        invalid(() -> plan("报消", dictionary, new SearchQueryPlan.Selection(null, 0, 2, 2)));
        invalid(() -> plan("报消", dictionary, new SearchQueryPlan.Selection(CORRECTION, 0, 3, 2)));
        invalid(() -> plan("报消", dictionary, new SearchQueryPlan.Selection(CORRECTION, 0, 2, 0)));
        assertThat(dictionary.probeReads).isZero();
    }

    @Test
    void appendedAliasContainingAnotherRegisteredTermDoesNotStartRecursiveExpansion() {
        var dictionary = new SyntheticTermDictionary().group(1, "管理平台", "检索用语", synonym("成本管理系统"))
                .group(2, "成本管理", "另一关系", synonym("费用管理"));
        var query = plan("管理平台", dictionary);
        assertThat(query.executionText()).isEqualTo("管理平台\n成本管理系统");
        assertThat(query.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::termId).containsExactly(1L);
        assertThat(dictionary.aliasReads).containsOnlyKeys(1L);
        assertThat(dictionary.relationReads).isEqualTo(1);
    }

    @Test
    void selectedGroupsCanonicalNameIsRetainedEvenWhenAnotherGroupsAliasUsesIt() {
        var dictionary = new SyntheticTermDictionary().group(1, "项目管理", "标准名称", abbreviation("PM"))
                .group(2, "另一组", "别名碰撞", synonym("项目管理"));
        var plan = plan("PM", dictionary);
        assertThat(plan.executionText()).isEqualTo("PM\n项目管理");
        assertThat(plan.appliedTerms().getFirst().expressions()).containsExactly("项目管理");
        assertThat(dictionary.ownerReads).doesNotContainKey("项目管理");
        assertThat(dictionary.aliasReads).containsOnlyKeys(1L);
    }

    @Test
    void replacedExpressionCanBeAddedByAnotherAutomaticMatch() {
        var dictionary = new SyntheticTermDictionary().group(1, "PM", "项目", synonym("项目"))
                .group(2, "产品", "产品", abbreviation("PM"));
        var selection = new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 2);
        var result = plan("PM 项目", dictionary, selection);
        assertThat(result.effectiveQuery()).isEqualTo("产品 项目");
        assertThat(result.executionText()).isEqualTo("产品 项目\nPM");
        assertThat(result.appliedTerms()).extracting(SearchQueryPlan.AppliedTerm::termId).containsExactly(2L, 1L);
        assertThat(result.appliedTerms().getFirst().expressions()).isEmpty();
        assertThat(result.appliedTerms().getLast().expressions()).containsExactly("PM");
        assertThat(result.selections()).containsExactly(selection);
        assertThat(result.suggestions()).isEmpty();

        var reordered = plan("项目 😀pm", dictionary, new SearchQueryPlan.Selection(DISAMBIGUATION, 4, 6, 2));
        assertThat(reordered.effectiveQuery()).isEqualTo("项目 😀产品");
        assertThat(reordered.executionText()).isEqualTo("项目 😀产品\nPM");
        assertThat(reordered.appliedTerms().getFirst().expressions()).containsExactly("PM");
        assertThat(reordered.appliedTerms().getLast().matchedText()).isEqualTo("pm");
        assertThat(reordered.appliedTerms().getLast().expressions()).isEmpty();
    }

    @Test
    void unchangedOccurrencePreventsExpansionUntilEveryOccurrenceIsReplaced() {
        var dictionary = new SyntheticTermDictionary().group(1, "PM", "项目", synonym("项目"))
                .group(2, "产品", "产品", abbreviation("PM"));
        var first = new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 2);
        var partial = plan("PM 项目 pm", dictionary, first);
        assertThat(partial.effectiveQuery()).isEqualTo("产品 项目 pm");
        assertThat(partial.executionText()).isEqualTo("产品 项目 pm");
        assertThat(partial.appliedTerms().getLast().expressions()).isEmpty();
        assertThat(partial.suggestions()).extracting(SearchQueryPlan.Suggestion::start).containsExactly(6);

        var complete = plan("PM 项目 pm", dictionary, first, new SearchQueryPlan.Selection(DISAMBIGUATION, 6, 8, 2));
        assertThat(complete.executionText()).isEqualTo("产品 项目 产品\nPM");
        assertThat(complete.appliedTerms().get(1).expressions()).containsExactly("PM");
        assertThat(complete.suggestions()).isEmpty();
    }

    @Test
    void disabledGroupsAndMappingsDoNotProduceExpansionOrSuggestions() {
        assertThat(plan("OA", SyntheticTermDictionary.examples().disabled(1)).appliedTerms()).isEmpty();
        assertThat(plan("报消", SyntheticTermDictionary.examples().disabled(2)).suggestions()).isEmpty();
        assertThat(plan("报消", SyntheticTermDictionary.examples().disabledCorrection("报消")).suggestions()).isEmpty();
    }

    @Test
    void repeatedGroupLoadsItsAliasesOnceAndUsesSameDeduplicatedExpressionList() {
        var dictionary = SyntheticTermDictionary.examples();
        var plan = plan("OA 和 OA", dictionary);
        assertThat(plan.appliedTerms()).hasSize(2);
        assertThat(plan.appliedTerms().getFirst().expressions()).containsExactly("办公自动化", "旧办公系统");
        assertThat(plan.appliedTerms().getLast().expressions()).isEqualTo(plan.appliedTerms().getFirst().expressions());
        assertThat(plan.executionText()).isEqualTo("OA 和 OA\n办公自动化\n旧办公系统");
        assertThat(dictionary.aliasReads).containsEntry(1L, 1);
        var input = new ArrayList<SearchQueryPlan.Selection>();
        input.add(new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2));
        var selected = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, "报消", 8000, input,
                SyntheticTermDictionary.examples(), SearchQueryPlanTest::checkpoint);
        input.clear();
        assertThat(selected.selections()).hasSize(1);
    }

    static SearchQueryPlan plan(String query, SyntheticTermDictionary dictionary, SearchQueryPlan.Selection... selections) {
        return new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, query, 8000, List.of(selections), dictionary,
                SearchQueryPlanTest::checkpoint);
    }

    private static void invalid(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SearchFailure.class,
                error -> assertThat(error.kind()).isEqualTo(SearchFailure.Kind.INVALID_INPUT));
    }

    static void checkpoint() {
    }
}
