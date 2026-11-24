package com.zhishiku.kb.app.search.expansion;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.CORRECTION;
import static com.zhishiku.kb.app.search.expansion.SearchQueryPlan.SelectionKind.DISAMBIGUATION;
import static org.assertj.core.api.Assertions.assertThat;

/** 样例只描述规则期望，不含真实业务语料、检索命中或排序质量评分。 */
class SyntheticQuerySamplesTest {
    private record ExpectedMatch(int start, int end, String text) {
    }

    private record Sample(String original, List<SearchQueryPlan.Selection> selections, List<ExpectedMatch> matches,
                          String effective, List<String> expressions, List<SearchQueryPlan.SelectionKind> suggestionKinds) {
        @Override
        public String toString() {
            return "SyntheticQuerySample[redacted]";
        }
    }

    @ParameterizedTest(name = "合成规则样例 {index}")
    @MethodSource("samples")
    void eachSampleDeclaresExactMatchingReplacementAndAddedExpressions(Sample sample) {
        var plan = new SearchQueryPlanner().plan(SearchQueryPlan.Mode.ASSISTED, sample.original(), 8000, sample.selections(),
                SyntheticTermDictionary.examples(), SearchQueryPlanTest::checkpoint);
        assertThat(plan.originalQuery()).isEqualTo(sample.original());
        assertThat(plan.effectiveQuery()).isEqualTo(sample.effective());
        var actualMatches = new ArrayList<ExpectedMatch>();
        plan.appliedTerms().forEach(item -> actualMatches.add(new ExpectedMatch(item.start(), item.end(), item.matchedText())));
        plan.suggestions().forEach(item -> actualMatches.add(new ExpectedMatch(item.start(), item.end(), item.sourceText())));
        assertThat(actualMatches.stream().sorted(Comparator.comparingInt(ExpectedMatch::start)).toList()).containsExactlyElementsOf(sample.matches());
        var added = new LinkedHashSet<String>();
        plan.appliedTerms().forEach(item -> added.addAll(item.expressions()));
        assertThat(added).containsExactlyElementsOf(sample.expressions());
        String execution = sample.effective() + (sample.expressions().isEmpty() ? "" : "\n" + String.join("\n", sample.expressions()));
        assertThat(plan.executionText()).isEqualTo(execution);
        assertThat(plan.suggestions()).extracting(SearchQueryPlan.Suggestion::kind).containsExactlyElementsOf(sample.suggestionKinds());
    }

    private static Stream<Sample> samples() {
        String longQuery = "请说明企业 OA 使用说明和报消流程的材料要求";
        return Stream.of(
                new Sample("OA 使用说明", List.of(), List.of(new ExpectedMatch(0, 2, "OA")), "OA 使用说明",
                        List.of("办公自动化", "旧办公系统"), List.of()),
                new Sample("旧办公系统入口", List.of(), List.of(new ExpectedMatch(0, 5, "旧办公系统")), "旧办公系统入口",
                        List.of("办公自动化", "OA"), List.of()),
                new Sample(longQuery, List.of(), List.of(new ExpectedMatch(6, 8, "OA"), new ExpectedMatch(14, 16, "报消")), longQuery,
                        List.of("办公自动化", "旧办公系统"), List.of(CORRECTION)),
                new Sample(longQuery, List.of(new SearchQueryPlan.Selection(CORRECTION, 14, 16, 2)),
                        List.of(new ExpectedMatch(6, 8, "OA"), new ExpectedMatch(14, 16, "报消")), "请说明企业 OA 使用说明和报销流程的材料要求",
                        List.of("办公自动化", "旧办公系统", "费用报销"), List.of()),
                new Sample("报消流程", List.of(), List.of(new ExpectedMatch(0, 2, "报消")), "报消流程", List.of(), List.of(CORRECTION)),
                new Sample("报消流程", List.of(new SearchQueryPlan.Selection(CORRECTION, 0, 2, 2)), List.of(new ExpectedMatch(0, 2, "报消")),
                        "报销流程", List.of("费用报销"), List.of()),
                new Sample("PM流程", List.of(), List.of(new ExpectedMatch(0, 2, "PM")), "PM流程", List.of(), List.of(DISAMBIGUATION)),
                new Sample("PM流程", List.of(new SearchQueryPlan.Selection(DISAMBIGUATION, 0, 2, 3)), List.of(new ExpectedMatch(0, 2, "PM")),
                        "项目管理流程", List.of("项目协调"), List.of()),
                new Sample("LOAD流程", List.of(), List.of(), "LOAD流程", List.of(), List.of()),
                new Sample("报肖流程", List.of(), List.of(), "报肖流程", List.of(), List.of()),
                new Sample("未登记资料问法", List.of(), List.of(), "未登记资料问法", List.of(), List.of()),
                new Sample("😀OA 办公自动化 OA", List.of(), List.of(new ExpectedMatch(1, 3, "OA"), new ExpectedMatch(4, 9, "办公自动化"),
                        new ExpectedMatch(10, 12, "OA")), "😀OA 办公自动化 OA", List.of("旧办公系统"), List.of()));
    }
}
