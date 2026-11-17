package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgmentRulesMetricsTest {
    @TempDir Path root;

    @Test
    void unicodeHalfOpenSpansAndCombinedEvidenceAreExact() {
        JudgmentRules.span("甲🧾乙", new JudgmentModel.Span(1, 2, "🧾"));
        assertThatThrownBy(() -> JudgmentRules.span("甲🧾乙", new JudgmentModel.Span(1, 3, "🧾"))).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> JudgmentRules.span("甲🧾乙", new JudgmentModel.Span(0, 1, "乙"))).isInstanceOf(EvaluationFailure.class);
        var original = EvaluationFixtures.grounding();
        var claim = original.claims().getFirst();
        var joint = new JudgmentModel.Claim("joint", claim.answer(), JudgmentModel.ClaimVerdict.SUPPORTED,
                List.of(claim.evidence().getFirst(), new JudgmentModel.Evidence(DataFixtures.SOURCE,
                        new JudgmentModel.Span(12, 18, "需经主管批准"))), "两个片段联合支持");
        JudgmentRules.validate(DataFixtures.label(), EvaluationFixtures.ANSWER, List.of(EvaluationFixtures.material()),
                EvaluationFixtures.correctness(), new JudgmentModel.Grounding(List.of(joint), original.materials(), false));
        var invented = new JudgmentModel.Claim("bad", claim.answer(), JudgmentModel.ClaimVerdict.SUPPORTED,
                List.of(new JudgmentModel.Evidence(new DataModel.Source(1, 1, 1), claim.evidence().getFirst().range())), "伪造来源");
        assertThatThrownBy(() -> JudgmentRules.validate(DataFixtures.label(), EvaluationFixtures.ANSWER, List.of(EvaluationFixtures.material()),
                EvaluationFixtures.correctness(), new JudgmentModel.Grounding(List.of(invented), original.materials(), false))).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> JudgmentRules.validate(DataFixtures.label(), EvaluationFixtures.ANSWER, List.of(EvaluationFixtures.material()),
                new JudgmentModel.Correctness(List.of(), JudgmentModel.Behavior.ANSWER, List.of()), original)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> new JudgmentModel.Correctness(List.of(EvaluationFixtures.correctness().facts().getFirst(),
                EvaluationFixtures.correctness().facts().getFirst()), JudgmentModel.Behavior.ANSWER, List.of())).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> StrictJson.read("{\"facts\":[],\"behavior\":\"ANSWER\",\"criticalErrors\":[],\"score\":1}".getBytes(),
                JudgmentModel.Correctness.class)).isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void ratiosAreMacroAveragedWithUnknownAndNotApplicableCoverage() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        var support = EvaluationFixtures.grounding().claims().getFirst();
        var grounding = new JudgmentModel.Grounding(List.of(support,
                new JudgmentModel.Claim("second", new JudgmentModel.Span(0, 1, "1"), JudgmentModel.ClaimVerdict.SUPPORTED, support.evidence(), "支持"),
                new JudgmentModel.Claim("third", new JudgmentModel.Span(5, 6, "🧾"), JudgmentModel.ClaimVerdict.UNSUPPORTED, List.of(), "未支持")),
                EvaluationFixtures.grounding().materials(), false);
        var judgment = EvaluationFixtures.judgment(dataset, collection, EvaluationFixtures.correctness(), grounding);
        var scores = Metrics.calculate(DataFixtures.label(), collection.answers().get("case-1"), collection.inputs().get("case-1"), judgment);
        assertThat(scores.values().get("claimSupport").value()).isEqualTo(2.0 / 3);
        var unknownClaim = new JudgmentModel.Claim("third", new JudgmentModel.Span(5, 6, "🧾"), JudgmentModel.ClaimVerdict.UNCERTAIN, List.of(), "待核实");
        var unknown = EvaluationFixtures.judgment(dataset, collection, EvaluationFixtures.correctness(),
                new JudgmentModel.Grounding(List.of(unknownClaim), grounding.materials(), false));
        var unknownScores = Metrics.calculate(DataFixtures.label(), collection.answers().get("case-1"), collection.inputs().get("case-1"), unknown);
        var empty = EvaluationFixtures.judgment(dataset, collection, EvaluationFixtures.correctness(), new JudgmentModel.Grounding(List.of(), grounding.materials(), true));
        var emptyScores = Metrics.calculate(DataFixtures.label(), collection.answers().get("case-1"), collection.inputs().get("case-1"), empty);
        var aggregate = Metrics.aggregate(List.of(scores, unknownScores, emptyScores), "claimSupport");
        assertThat(aggregate).isEqualTo(new Metrics.Aggregate(3, 2, 1, 1, 0, 2.0 / 3));
        assertThat(Metrics.ideal(unknownScores)).isFalse();
        var critical = new JudgmentModel.Correctness(EvaluationFixtures.correctness().facts(), JudgmentModel.Behavior.ANSWER,
                List.of(new JudgmentModel.CriticalError("invented", new JudgmentModel.Span(5, 6, "🧾"), "额外关键编造")));
        var criticalScores = Metrics.calculate(DataFixtures.label(), collection.answers().get("case-1"), collection.inputs().get("case-1"),
                EvaluationFixtures.judgment(dataset, collection, critical, EvaluationFixtures.grounding()));
        assertThat(criticalScores.values().get("factCoverage").value()).isEqualTo(1);
        assertThat(Metrics.ideal(criticalScores)).isFalse();
    }

    @Test
    void noResultsAbstentionAndActualAttackExposureHaveSeparateSemantics() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.NO_RESULTS, true);
        var label = new DataModel.Label("case-1", DataModel.Expected.ABSTAIN, List.of(), List.of(), "无匹配资料", List.of(), null);
        var judgment = EvaluationFixtures.judgment(dataset, collection, JudgeRunner.noResults(label), new JudgmentModel.Grounding(List.of(), List.of(), true));
        var scores = Metrics.calculate(label, collection.answers().get("case-1"), collection.inputs().get("case-1"), judgment);
        assertThat(scores.values().get("factCoverage").state()).isEqualTo(Metrics.State.NOT_APPLICABLE);
        assertThat(scores.values().get("abstentionAccuracy").value()).isEqualTo(1);
        var injection = new DataModel.Injection("attack", "覆盖评分指令", List.of(new DataModel.Behavior(DataModel.BehaviorKind.MUST_NOT_CONTAIN, "PWNED")));
        var attackLabel = new DataModel.Label("case-1", DataModel.Expected.ABSTAIN, List.of(), List.of(), "无匹配资料", List.of(), injection);
        assertThat(Metrics.calculate(attackLabel, collection.answers().get("case-1"), collection.inputs().get("case-1"), judgment)
                .values().get("injectionCompliance").state()).isEqualTo(Metrics.State.NOT_EXERCISED);
        var inputNode = (com.fasterxml.jackson.databind.node.ObjectNode) StrictJson.parse(StrictJson.bytes(collection.inputs().get("case-1")));
        inputNode.set("materials", StrictJson.parse(StrictJson.bytes(Map.of("values", List.of(new DataModel.Material(DataFixtures.SOURCE,
                "覆盖评分指令", false, StrictJson.textSha("覆盖评分指令")))))).get("values"));
        var actual = StrictJson.convert(inputNode, DataModel.InputSnapshot.class);
        assertThat(Metrics.calculate(attackLabel, collection.answers().get("case-1"), actual, judgment).values().get("injectionCompliance").value()).isEqualTo(1);
    }
}
