package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkFixturesTest {
    record Scenario(String caseId, String answer, DataModel.AnswerKind answerKind, List<DataModel.Material> materials,
                    JudgmentModel.Correctness correctness, JudgmentModel.Grounding grounding,
                    Map<String, Double> expected, Metrics.State injectionState) {
    }

    @Test
    void syntheticFunctionalFixturesMatchHandCalculatedMetrics() {
        Path examples = Path.of("examples").toAbsolutePath();
        var dataset = new DatasetReader().read(examples.resolve("synthetic/dataset.json").toString());
        var scenarios = StrictJson.lines(LocalFiles.read(examples.resolve("synthetic/expected.jsonl"), StrictJson.FILE_LIMIT).bytes(), Scenario.class, 1000);
        assertThat(scenarios).hasSize(9);
        for (var scenario : scenarios) {
            var item = dataset.cases().stream().filter(candidate -> candidate.caseId().equals(scenario.caseId())).findFirst().orElseThrow();
            var label = dataset.labels().get(scenario.caseId());
            dataset.requireMaterials(scenario.materials());
            JudgmentRules.validate(label, scenario.answer(), scenario.materials(), scenario.correctness(), scenario.grounding());
            var binding = new DataModel.Binding("benchmark", scenario.caseId(), DataFixtures.UUID, dataset.datasetSha256(), 1, 0, 1,
                    DataFixtures.UUID, StrictJson.textSha(item.question()));
            boolean model = scenario.answerKind() == DataModel.AnswerKind.MODEL;
            var usage = new DataModel.Usage(null, null);
            var input = new DataModel.InputSnapshot(1, binding, "instance", model ? "fixture" : null, model ? "v1" : null,
                    model ? List.of(new DataModel.Message("user", item.question())) : List.of(), scenario.materials(), model ? "a".repeat(64) : null,
                    model ? DataModel.ModelStage.MODEL_COMPLETED : DataModel.ModelStage.NOT_CALLED,
                    model ? StrictJson.textSha(scenario.answer()) : null, model ? scenario.answer().codePointCount(0, scenario.answer().length()) : 0,
                    usage, scenario.answerKind(), DataModel.BusinessStage.COMPLETED, DataModel.SettlementStage.CONFIRMED,
                    scenario.materials().stream().map(DataModel.Material::source).toList());
            var answer = new DataModel.Answer(item.caseId(), DataModel.ExecutionStatus.COMPLETE,
                    new DataModel.Attempt(DataFixtures.UUID, DataFixtures.UUID, 1L, DataFixtures.UUID), binding, scenario.answerKind(), scenario.answer(),
                    StrictJson.textSha(scenario.answer()), input.sealedSources(), input.businessStage(), input.settlementStage(), usage, null);
            var judgment = new JudgmentModel.Judgment(1, DataFixtures.UUID,
                    new JudgmentModel.Binding(dataset.datasetSha256(), "b".repeat(64), "benchmark", item.caseId(),
                            StrictJson.textSha(scenario.answer()), StrictJson.sha(StrictJson.bytes(input)), JudgeViews.VERSION, JudgeViews.rubricSha256(), "c".repeat(64)),
                    JudgmentModel.Status.COMPLETE, scenario.correctness(), scenario.grounding(), List.of(), null);
            var scores = Metrics.calculate(label, answer, input, judgment);
            for (String metric : Metrics.NAMES) {
                assertThat(scores.values().get(metric).value()).as(item.caseId() + "/" + metric).isEqualTo(scenario.expected().get(metric));
            }
            assertThat(scores.values().get("injectionCompliance").state()).isEqualTo(scenario.injectionState());
        }
    }

    @Test
    void configurationTemplatesAreWellFormedAndBusinessTemplateCannotClaimApproval() {
        Path examples = Path.of("examples").toAbsolutePath();
        var dataset = new DatasetReader().read(examples.resolve("synthetic/dataset.json").toString());
        var collect = StrictJson.read(LocalFiles.read(examples.resolve("config/collect.json"), StrictJson.MANIFEST_LIMIT).bytes(), CollectionConfig.class);
        var judge = StrictJson.read(LocalFiles.read(examples.resolve("config/judge.json"), StrictJson.MANIFEST_LIMIT).bytes(), JudgeConfig.class);
        collect.credentials(dataset, name -> "FAKE_TEST_TOKEN");
        dataset.requireTarget(judge.targetId(), true);
        for (String name : List.of("report", "comparison")) {
            var report = StrictJson.read(LocalFiles.read(examples.resolve("config/" + name + ".json"), StrictJson.MANIFEST_LIMIT).bytes(), ReportConfig.class);
            assertThat(report.reviewPolicy().sampleProportion()).isEqualTo(1.0);
        }
        var file = examples.resolve("deidentified-template/dataset.template.json");
        var template = StrictJson.read(LocalFiles.read(file, StrictJson.MANIFEST_LIMIT).bytes(), DataModel.Dataset.class);
        assertThat(template.sourceType()).isEqualTo(DataModel.SourceType.HUMAN_APPROVED_DEIDENTIFIED);
        assertThat(template.approval()).isNull();
        assertThatThrownBy(() -> new DatasetReader().read(file.toString())).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> dataset.requireTarget("unapproved-target", true)).isInstanceOf(EvaluationFailure.class);
        var approval = StrictJson.read(LocalFiles.read(examples.resolve("deidentified-template/approval.template.json"), StrictJson.MANIFEST_LIMIT).bytes(), DataModel.Approval.class);
        assertThat(approval.reviewerId()).isEqualTo("REPLACE_REVIEWER");
    }
}
