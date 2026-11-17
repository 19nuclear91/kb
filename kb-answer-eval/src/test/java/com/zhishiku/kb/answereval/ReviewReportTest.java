package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewReportTest {
    @TempDir Path root;
    DatasetReader.Batch dataset;
    CollectionReader.Run collection;
    JudgmentReader.Run judgments;
    final Reviews reviews = new Reviews();
    final Reports reports = new Reports();

    @BeforeEach
    void prepare() throws Exception {
        dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        new JudgeRunner().judge(dataset, collection, EvaluationFixtures.config(), "fake-key", EvaluationFixtures.goodJudge(),
                System::nanoTime, new OutputPublisher().preflight(root.resolve("judge").toString()));
        judgments = new JudgmentReader().read(root.resolve("judge/judgment.json").toString(), dataset, collection);
    }

    @Test
    void templateAndRevisionChainAreBoundIdempotentAndConflictRejecting() throws Exception {
        reviews.template(dataset, collection, judgments, config(List.of()), new OutputPublisher().preflight(root.resolve("template").toString()));
        var drafts = StrictJson.lines(Files.readAllBytes(root.resolve("template/reviews.jsonl")), ReviewModel.Review.class, 1000);
        assertThat(drafts).hasSize(1);
        assertThat(drafts.getFirst().binding()).isEqualTo(Reviews.binding(judgments.rows().get("case-1")));
        assertThatThrownBy(() -> reviews.merge(dataset, collection, judgments, Reviews.History.empty(), drafts)).isInstanceOf(EvaluationFailure.class);
        var first = review(judgments.rows().get("case-1"), 1, null, ReviewModel.Decision.CONFIRMED);
        var history = reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(first));
        assertThat(reviews.merge(dataset, collection, judgments, history, List.of(first))).isEqualTo(history);
        var second = review(judgments.rows().get("case-1"), 2, first.sha256(), ReviewModel.Decision.CONFIRMED);
        var full = reviews.merge(dataset, collection, judgments, history, List.of(second));
        assertThat(full.revisions()).hasSize(2);
        reviews.publish(dataset, collection, judgments, full, new OutputPublisher().preflight(root.resolve("history").toString()));
        assertThat(reviews.read(root.resolve("history/review.json").toString(), dataset, collection, judgments)).isEqualTo(full);
        var fork = review(judgments.rows().get("case-1"), 2, first.sha256(), ReviewModel.Decision.CONFIRMED);
        assertThatThrownBy(() -> reviews.merge(dataset, collection, judgments, full, List.of(fork))).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(second))).isInstanceOf(EvaluationFailure.class);
        ObjectNode changed = (ObjectNode) StrictJson.parse(StrictJson.bytes(first));
        changed.put("reason", "同标识异内容");
        assertThatThrownBy(() -> reviews.merge(dataset, collection, judgments, history, List.of(StrictJson.convert(changed, ReviewModel.Review.class))))
                .isInstanceOf(EvaluationFailure.class);
        changed.put("reason", "");
        assertThatThrownBy(() -> StrictJson.convert(changed, ReviewModel.Review.class)).isInstanceOf(EvaluationFailure.class);
        changed.put("reason", "复核");
        ((ObjectNode) changed.path("binding")).put("judgmentSha256", "0".repeat(64));
        assertThatThrownBy(() -> reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(StrictJson.convert(changed, ReviewModel.Review.class))))
                .isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void humanAdditionalClaimChangesEffectiveDenominatorButPreservesInitial() {
        var original = judgments.rows().get("case-1");
        List<JudgmentModel.Claim> claims = new ArrayList<>(original.grounding().claims());
        claims.add(new JudgmentModel.Claim("missed", new JudgmentModel.Span(5, 6, "🧾"), JudgmentModel.ClaimVerdict.UNSUPPORTED, List.of(), "补充遗漏结论"));
        var changed = new JudgmentModel.Judgment(1, original.judgeRunId(), original.binding(), original.status(), original.correctness(),
                new JudgmentModel.Grounding(claims, original.grounding().materials(), false), original.calls(), null);
        var review = review(changed, 1, null, ReviewModel.Decision.CONFIRMED);
        // 修订始终绑定不可变原判，不能绑定已修改的语义内容。
        review = new ReviewModel.Review(1, review.reviewId(), 1, null, Reviews.binding(original), "reviewer-1",
                ReviewModel.Decision.CONFIRMED, "补漏", changed.correctness(), changed.grounding());
        var history = reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(review));
        var report = reports.calculate(dataset, collection, judgments, history, config(List.of(new ReportConfig.Rule(null, "claimSupport", 0.9, null))), null);
        assertThat(report.status()).isEqualTo(Reports.Status.FAIL);
        assertThat(report.cases().getFirst().initial().values().get("claimSupport").denominator()).isEqualTo(1);
        assertThat(report.cases().getFirst().effective().values().get("claimSupport").value()).isEqualTo(0.5);
        assertThat(report.cases().getFirst().claimSources()).containsEntry("missed", Reports.Source.HUMAN);
        assertThat(judgments.rows().get("case-1").grounding().claims()).hasSize(1);
        assertThat(report.requiredCases()).containsExactly("case-1");
    }

    @Test
    void gatesRespectPriorityUnknownLabelErrorsAndExplicitThresholds() {
        var rules = List.of(new ReportConfig.Rule(null, "factCoverage", 1.0, null));
        assertThat(reports.calculate(dataset, collection, judgments, Reviews.History.empty(), config(rules), null).status()).isEqualTo(Reports.Status.REVIEW_REQUIRED);
        var history = reviewed();
        assertThat(reports.calculate(dataset, collection, judgments, history, config(rules), null).status()).isEqualTo(Reports.Status.PASS);
        assertThat(reports.calculate(dataset, collection, judgments, history, config(List.of()), null).status()).isEqualTo(Reports.Status.NOT_CONFIGURED);
        var labelError = reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(review(judgments.rows().get("case-1"), 1, null, ReviewModel.Decision.LABEL_ERROR)));
        assertThat(reports.calculate(dataset, collection, judgments, labelError, config(rules), null).status()).isEqualTo(Reports.Status.REVIEW_REQUIRED);
        assertThatThrownBy(() -> reports.calculate(dataset, collection, judgments, history,
                config(List.of(new ReportConfig.Rule(null, "injectionCompliance", 1.0, null))), null)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> reports.calculate(dataset, collection, judgments, history,
                config(List.of(new ReportConfig.Rule(null, "factCoverage", null, 0.1))), null)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> config(List.of(rules.getFirst(), rules.getFirst()))).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> new ReportConfig.Rule(null, "unknown", 0.5, null)).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> new ReportConfig.Policy("stratified-sha256-v1", 0)).isInstanceOf(EvaluationFailure.class);
        var original = judgments.rows().get("case-1");
        var uncertain = new JudgmentModel.Correctness(List.of(new JudgmentModel.Fact("amount", JudgmentModel.FactVerdict.UNCERTAIN, "仍待核实")),
                JudgmentModel.Behavior.ANSWER, List.of());
        var manualUnknown = new ReviewModel.Review(1, UUID.randomUUID().toString(), 1, null, Reviews.binding(original), "reviewer-1",
                ReviewModel.Decision.CONFIRMED, "无法消除不确定性", uncertain, original.grounding());
        var unknownHistory = reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(manualUnknown));
        assertThat(reports.calculate(dataset, collection, judgments, unknownHistory, config(rules), null).status()).isEqualTo(Reports.Status.REVIEW_REQUIRED);
    }

    @Test
    void reportsAreDeterministicTextFreeAndComparableOnlyWithSameIdentity() throws Exception {
        var config = config(List.of(new ReportConfig.Rule("amount", "factCoverage", 1.0, null)));
        var baseline = reports.calculate(dataset, collection, judgments, reviewed(), config, null);
        reports.publish(baseline, new OutputPublisher().preflight(root.resolve("report-a").toString()));
        reports.publish(baseline, new OutputPublisher().preflight(root.resolve("report-b").toString()));
        for (String file : List.of("report.json", "report.csv", "report.md", "complete.json")) {
            assertThat(Files.readAllBytes(root.resolve("report-a/" + file))).isEqualTo(Files.readAllBytes(root.resolve("report-b/" + file)));
            assertThat(Files.readString(root.resolve("report-a/" + file))).doesNotContain(DataFixtures.CORPUS, DataFixtures.item().question(),
                    EvaluationFixtures.ANSWER, "fake-key", "https://judge.invalid", "材料明确给出");
        }
        assertThat(reports.read(root.resolve("report-a/report.json").toString())).isEqualTo(baseline);
        assertThat(reports.calculate(dataset, collection, judgments, reviewed(), config,
                baseline).status()).isEqualTo(Reports.Status.PASS);
        var changedPolicy = new ReportConfig(1, new ReportConfig.Policy("stratified-sha256-v1", 0.5), config.rules());
        assertThatThrownBy(() -> reports.calculate(dataset, collection, judgments, reviewed(), changedPolicy, baseline)).isInstanceOf(EvaluationFailure.class);
        ObjectNode manifest = (ObjectNode) StrictJson.parse(StrictJson.bytes(collection.manifest()));
        manifest.put("aclSnapshotId", "other-acl");
        var changedCollection = new CollectionReader.Run(StrictJson.convert(manifest, DataModel.Collection.class), collection.sha256(),
                collection.answers(), collection.inputs(), collection.inputDigests());
        assertThatThrownBy(() -> reports.calculate(dataset, changedCollection, judgments, reviewed(), config, baseline)).isInstanceOf(EvaluationFailure.class);
        assertThat(baseline.identity().corpusState()).isEqualTo(DataModel.ClaimState.DECLARED_ONLY);
        for (String field : List.of("configurationSha256", "rubricSha256")) {
            ObjectNode changed = (ObjectNode) StrictJson.parse(StrictJson.bytes(judgments.manifest()));
            changed.put(field, "e".repeat(64));
            var changedJudge = new JudgmentReader.Run(StrictJson.convert(changed, JudgmentModel.Manifest.class), judgments.sha256(), judgments.rows());
            assertThatThrownBy(() -> reports.calculate(dataset, collection, changedJudge, reviewed(), config, baseline)).isInstanceOf(EvaluationFailure.class);
        }
        var changedDataset = new DatasetReader.Batch(dataset.dataset(), "d".repeat(64), dataset.cases(), dataset.labels(),
                dataset.corpus(), dataset.frozenFiles(), dataset.manifestFile());
        assertThatThrownBy(() -> reports.calculate(changedDataset, collection, judgments, reviewed(), config, baseline)).isInstanceOf(EvaluationFailure.class);
        ObjectNode changed = (ObjectNode) StrictJson.parse(StrictJson.bytes(collection.manifest()));
        ((ObjectNode) changed.get("identityBindings")).put("employee", 2);
        var differentUser = new CollectionReader.Run(StrictJson.convert(changed, DataModel.Collection.class), collection.sha256(),
                collection.answers(), collection.inputs(), collection.inputDigests());
        assertThatThrownBy(() -> reports.calculate(dataset, differentUser, judgments, reviewed(), config, baseline)).isInstanceOf(EvaluationFailure.class);
        changed = (ObjectNode) StrictJson.parse(StrictJson.bytes(collection.manifest()));
        changed.put("artifactId", "different-candidate").put("configurationSha256", "f".repeat(64));
        var candidate = new CollectionReader.Run(StrictJson.convert(changed, DataModel.Collection.class), collection.sha256(), collection.answers(),
                collection.inputs(), Map.of("case-1", "e".repeat(64)));
        assertThat(reports.calculate(dataset, candidate, judgments, reviewed(), config, baseline).status()).isEqualTo(Reports.Status.PASS);
        assertThat(baseline.modelComparison()).isEqualTo(new Reports.ModelComparison(false, 0, 1, 0));
        changed.put("model", judgments.manifest().model());
        ObjectNode sameInput = (ObjectNode) StrictJson.parse(StrictJson.bytes(collection.inputs().get("case-1")));
        sameInput.put("model", judgments.manifest().model());
        var sameModel = new CollectionReader.Run(StrictJson.convert(changed, DataModel.Collection.class), collection.sha256(), collection.answers(),
                Map.of("case-1", StrictJson.convert(sameInput, DataModel.InputSnapshot.class)), collection.inputDigests());
        assertThat(reports.calculate(dataset, sameModel, judgments, reviewed(), config, null).modelComparison())
                .isEqualTo(new Reports.ModelComparison(true, 1, 0, 0));
    }

    private Reviews.History reviewed() {
        return reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(review(judgments.rows().get("case-1"), 1, null, ReviewModel.Decision.CONFIRMED)));
    }

    @Test
    void reportsKeepDatasetProvenanceAndExecutionScopeInEveryFormat() throws Exception {
        for (var source : DataModel.SourceType.values()) {
            Path dataFile = DataFixtures.dataset(root.resolve(source.name()));
            ObjectNode node = (ObjectNode) StrictJson.parse(Files.readAllBytes(dataFile));
            node.put("sourceType", source.name());
            if (source == DataModel.SourceType.HUMAN_APPROVED_DEIDENTIFIED) {
                node.set("approval", StrictJson.parse(StrictJson.bytes(new DataModel.Approval("reviewer-1", "v1",
                        DatasetReader.approvalDigest(StrictJson.convert(node, DataModel.Dataset.class))))));
            }
            Files.write(dataFile, StrictJson.bytes(node));
            var input = new DatasetReader().read(dataFile.toString());
            var captured = EvaluationFixtures.collection(input, root.resolve(source + "-collection"), DataModel.AnswerKind.MODEL, true);
            Path judged = root.resolve(source + "-judge");
            new JudgeRunner().judge(input, captured, EvaluationFixtures.config(), "fake-key", EvaluationFixtures.goodJudge(),
                    System::nanoTime, new OutputPublisher().preflight(judged.toString()));
            var assessed = new JudgmentReader().read(judged.resolve("judgment.json").toString(), input, captured);
            var history = reviews.merge(input, captured, assessed, Reviews.History.empty(),
                    List.of(review(assessed.rows().get("case-1"), 1, null, ReviewModel.Decision.CONFIRMED)));
            var report = reports.calculate(input, captured, assessed, history,
                    config(List.of(new ReportConfig.Rule(null, "factCoverage", 1.0, null))), null);
            assertThat(report.status()).isEqualTo(Reports.Status.PASS);
            assertThat(report.identity().dataset()).isEqualTo(new Reports.DatasetSource(input.dataset().datasetId(),
                    input.dataset().datasetVersion(), source));
            assertThat(report.executionScope()).isEqualTo(new Reports.ExecutionScope(DataModel.InvocationOrigin.TEST_DOUBLE,
                    DataModel.InvocationOrigin.TEST_DOUBLE, DataModel.InvocationOrigin.TEST_DOUBLE));
            Path output = root.resolve(source + "-report");
            reports.publish(report, new OutputPublisher().preflight(output.toString()));
            assertThat(reports.read(output.resolve("report.json").toString())).isEqualTo(report);
            var csv = Files.readAllLines(output.resolve("report.csv"));
            var columns = List.of(csv.getFirst().split(",", -1));
            for (String line : csv.subList(1, csv.size())) {
                var values = line.split(",", -1);
                assertThat(values[columns.indexOf("sourceType")]).isEqualTo(source.name());
                assertThat(values[columns.indexOf("datasetId")]).isEqualTo(input.dataset().datasetId());
                assertThat(values[columns.indexOf("judgeModel")]).isEqualTo("TEST_DOUBLE");
            }
            // 仅核对报告数据表的序列化值，不检查说明文案。
            String row = "|" + input.dataset().datasetId() + "|" + input.dataset().datasetVersion()
                    + "|" + source + "|TEST_DOUBLE|TEST_DOUBLE|TEST_DOUBLE|";
            assertThat(Files.readAllLines(output.resolve("report.md"))).contains(row);
        }
    }

    @Test
    void httpAndMissingOriginNeverBecomeVerifiedRealExecutionOrMixWithStubBaseline() {
        var config = config(List.of());
        var baseline = reports.calculate(dataset, collection, judgments, reviewed(), config, null);
        for (var origin : List.of(DataModel.InvocationOrigin.HTTP_UNVERIFIED, DataModel.InvocationOrigin.UNKNOWN)) {
            ObjectNode manifest = (ObjectNode) StrictJson.parse(StrictJson.bytes(collection.manifest()));
            ObjectNode judgeManifest = (ObjectNode) StrictJson.parse(StrictJson.bytes(judgments.manifest()));
            if (origin == DataModel.InvocationOrigin.UNKNOWN) {
                manifest.remove("invocationOrigin");
                judgeManifest.remove("invocationOrigin");
            } else {
                manifest.put("invocationOrigin", origin.name());
                judgeManifest.put("invocationOrigin", origin.name());
            }
            var captured = new CollectionReader.Run(StrictJson.convert(manifest, DataModel.Collection.class), collection.sha256(),
                    collection.answers(), collection.inputs(), collection.inputDigests());
            var assessed = new JudgmentReader.Run(StrictJson.convert(judgeManifest, JudgmentModel.Manifest.class), judgments.sha256(), judgments.rows());
            assertThat(reports.calculate(dataset, captured, assessed, reviewed(), config, null).executionScope())
                    .isEqualTo(new Reports.ExecutionScope(origin, origin, origin));
            assertThatThrownBy(() -> reports.calculate(dataset, captured, assessed, reviewed(), config, baseline))
                    .isInstanceOf(EvaluationFailure.class);
        }
    }

    @Test
    void noResultsReportsNeverClaimModelOrJudgeInvocation() throws Exception {
        var captured = EvaluationFixtures.collection(dataset, root.resolve("no-results"), DataModel.AnswerKind.NO_RESULTS, true);
        Path judged = root.resolve("no-results-judge");
        new JudgeRunner().judge(dataset, captured, EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
            throw new AssertionError("合法无结果不得调用裁判");
        }, System::nanoTime, new OutputPublisher().preflight(judged.toString()));
        var assessed = new JudgmentReader().read(judged.resolve("judgment.json").toString(), dataset, captured);
        var report = reports.calculate(dataset, captured, assessed, Reviews.History.empty(), config(List.of()), null);
        assertThat(report.executionScope()).isEqualTo(new Reports.ExecutionScope(DataModel.InvocationOrigin.TEST_DOUBLE,
                DataModel.InvocationOrigin.NOT_CALLED, DataModel.InvocationOrigin.NOT_CALLED));
        var rules = config(List.of(new ReportConfig.Rule(null, "factCoverage", 1.0, null)));
        var baseline = reports.calculate(dataset, collection, judgments, reviewed(), rules, null);
        var history = reviews.merge(dataset, captured, assessed, Reviews.History.empty(),
                List.of(review(assessed.rows().get("case-1"), 1, null, ReviewModel.Decision.CONFIRMED)));
        assertThat(reports.calculate(dataset, captured, assessed, history, rules, baseline).status()).isEqualTo(Reports.Status.FAIL);
    }

    @Test
    void samplingIsStableStratifiedAndDoesNotShrinkAfterCorrections() {
        List<DataModel.Case> cases = new ArrayList<>();
        Map<String, DataModel.Label> labels = new java.util.LinkedHashMap<>();
        Map<String, DataModel.Answer> answers = new java.util.LinkedHashMap<>();
        Map<String, DataModel.InputSnapshot> inputs = new java.util.LinkedHashMap<>();
        Map<String, JudgmentModel.Judgment> rows = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 10; index++) {
            String id = "case-" + index;
            cases.add(new DataModel.Case(id, "employee", "q", index == 9 ? List.of("rare", "common") : List.of("common"), null));
            labels.put(id, DataFixtures.label());
            answers.put(id, collection.answers().get("case-1"));
            inputs.put(id, collection.inputs().get("case-1"));
            rows.put(id, judgments.rows().get("case-1"));
        }
        var expanded = new DatasetReader.Batch(dataset.dataset(), dataset.datasetSha256(), cases, labels, dataset.corpus(), dataset.frozenFiles(), dataset.manifestFile());
        var collected = new CollectionReader.Run(collection.manifest(), collection.sha256(), answers, inputs, Map.of());
        var judged = new JudgmentReader.Run(judgments.manifest(), judgments.sha256(), rows);
        var small = new ReportConfig(1, new ReportConfig.Policy("stratified-sha256-v1", 0.1), List.of());
        var required = reviews.required(expanded, collected, judged, small);
        assertThat(required).contains("case-9").hasSizeBetween(1, 2);
        assertThat(reviews.required(expanded, collected, judged, small)).isEqualTo(required);
        assertThat(reviews.required(expanded, collected, judged, config(List.of()))).hasSize(10).containsAll(required);
        var original = judgments.rows().get("case-1");
        var bad = new JudgmentModel.Judgment(1, original.judgeRunId(), original.binding(), original.status(),
                new JudgmentModel.Correctness(List.of(new JudgmentModel.Fact("amount", JudgmentModel.FactVerdict.MISSING, "缺失")),
                        JudgmentModel.Behavior.ANSWER, List.of()), original.grounding(), original.calls(), null);
        rows.put("case-8", bad);
        var problematic = new JudgmentReader.Run(judgments.manifest(), judgments.sha256(), rows);
        assertThat(reviews.required(expanded, collected, problematic, small)).contains("case-8", "case-9");
    }

    @Test
    void incompleteCannotBeOverriddenAndCriticalErrorsRemainVisibleBeforeReview() {
        var original = judgments.rows().get("case-1");
        var failed = new JudgmentModel.Judgment(1, original.judgeRunId(), original.binding(), JudgmentModel.Status.JUDGE_FAILED,
                null, null, List.of(), EvaluationFailure.Code.PROTOCOL);
        ObjectNode manifest = (ObjectNode) StrictJson.parse(StrictJson.bytes(judgments.manifest()));
        manifest.put("complete", false);
        var incomplete = new JudgmentReader.Run(StrictJson.convert(manifest, JudgmentModel.Manifest.class), judgments.sha256(), Map.of("case-1", failed));
        var forged = new ReviewModel.Review(1, UUID.randomUUID().toString(), 1, null, Reviews.binding(failed), "reviewer-1",
                ReviewModel.Decision.CONFIRMED, "试图覆盖协议失败", original.correctness(), original.grounding());
        assertThatThrownBy(() -> reviews.merge(dataset, collection, incomplete, Reviews.History.empty(), List.of(forged))).isInstanceOf(EvaluationFailure.class);
        assertThat(reports.calculate(dataset, collection, incomplete, Reviews.History.empty(), config(List.of()), null).status()).isEqualTo(Reports.Status.INCOMPLETE);
        var critical = new JudgmentModel.Correctness(original.correctness().facts(), JudgmentModel.Behavior.ANSWER,
                List.of(new JudgmentModel.CriticalError("extra", new JudgmentModel.Span(5, 6, "🧾"), "关键错误")));
        var bad = new JudgmentModel.Judgment(1, original.judgeRunId(), original.binding(), original.status(), critical, original.grounding(), original.calls(), null);
        var badRun = new JudgmentReader.Run(judgments.manifest(), judgments.sha256(), Map.of("case-1", bad));
        var before = reports.calculate(dataset, collection, badRun, Reviews.History.empty(), config(List.of()), null);
        assertThat(before.status()).isEqualTo(Reports.Status.REVIEW_REQUIRED);
        assertThat(before.knownFailures()).contains(new Reports.Failure(null, null, "CRITICAL_ERROR"));
        var history = reviews.merge(dataset, collection, badRun, Reviews.History.empty(), List.of(review(bad, 1, null, ReviewModel.Decision.CONFIRMED)));
        assertThat(reports.calculate(dataset, collection, badRun, history, config(List.of()), null).status()).isEqualTo(Reports.Status.FAIL);
        var correction = new ReviewModel.Review(1, UUID.randomUUID().toString(), 2, history.latest().get("case-1").sha256(), Reviews.binding(bad), "reviewer-1",
                ReviewModel.Decision.CONFIRMED, "确认不构成关键错误", original.correctness(), original.grounding());
        var corrected = reviews.merge(dataset, collection, badRun, history, List.of(correction));
        assertThat(reports.calculate(dataset, collection, badRun, corrected, config(List.of()), null).requiredCases()).containsExactly("case-1");
    }

    @Test
    void unroundedToleranceAndRegressionUseEffectiveValues() throws Exception {
        var baseline = reports.calculate(dataset, collection, judgments, reviewed(), config(List.of()), null);
        var original = judgments.rows().get("case-1");
        var supported = original.grounding().claims().getFirst();
        var grounding = new JudgmentModel.Grounding(List.of(supported,
                new JudgmentModel.Claim("two", new JudgmentModel.Span(0, 1, "1"), JudgmentModel.ClaimVerdict.SUPPORTED, supported.evidence(), "支持"),
                new JudgmentModel.Claim("three", new JudgmentModel.Span(5, 6, "🧾"), JudgmentModel.ClaimVerdict.UNSUPPORTED, List.of(), "未支持")), original.grounding().materials(), false);
        var review = new ReviewModel.Review(1, UUID.randomUUID().toString(), 1, null, Reviews.binding(original), "reviewer-1",
                ReviewModel.Decision.CONFIRMED, "完整补漏", original.correctness(), grounding);
        var history = reviews.merge(dataset, collection, judgments, Reviews.History.empty(), List.of(review));
        var tolerant = config(List.of(new ReportConfig.Rule(null, "claimSupport", 2.0 / 3 + 5e-13, 1.0 / 3)));
        assertThat(reports.calculate(dataset, collection, judgments, history, tolerant, baseline).status()).isEqualTo(Reports.Status.PASS);
        var strict = config(List.of(new ReportConfig.Rule(null, "claimSupport", 2.0 / 3 + 2e-12, 0.3)));
        var failed = reports.calculate(dataset, collection, judgments, history, strict, baseline);
        assertThat(failed.status()).isEqualTo(Reports.Status.FAIL);
        assertThat(failed.regressions()).containsExactly(new Reports.Regression("case-1", "claimSupport", 1.0, 2.0 / 3));
        assertThat(failed.knownFailures()).hasSize(2);
        reports.publish(failed, new OutputPublisher().preflight(root.resolve("delta").toString()));
        var csv = Files.readAllLines(root.resolve("delta/report.csv"));
        var columns = List.of(csv.getFirst().split(",", -1));
        var values = csv.stream().filter(line -> line.startsWith("case-1,claimSupport,")).findFirst().orElseThrow().split(",", -1);
        assertThat(Double.parseDouble(values[columns.indexOf("regressionBaseline")])).isEqualTo(1.0);
        assertThat(Double.parseDouble(values[columns.indexOf("regressionAmount")])).isEqualTo(1.0 - 2.0 / 3);
    }

    private static ReportConfig config(List<ReportConfig.Rule> rules) {
        return new ReportConfig(1, new ReportConfig.Policy("stratified-sha256-v1", 1), rules);
    }

    private static ReviewModel.Review review(JudgmentModel.Judgment judgment, int revision, String previous, ReviewModel.Decision decision) {
        return new ReviewModel.Review(1, UUID.randomUUID().toString(), revision, previous, Reviews.binding(judgment), "reviewer-1", decision,
                "人工核对", judgment.correctness(), judgment.grounding());
    }
}
