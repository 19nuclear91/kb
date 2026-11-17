package com.zhishiku.kb.answereval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 报告只使用已冻结的本地资料，公开汇总不携带问题、答案、证据或自由文本理由。 */
public final class Reports {
    public static final double TOLERANCE = 1e-12;
    public enum Status {
        INVALID(2), INCOMPLETE(3), REVIEW_REQUIRED(4), FAIL(1), NOT_CONFIGURED(0), PASS(0);
        private final int exitCode;
        Status(int exitCode) {
            this.exitCode = exitCode;
        }
        public int exitCode() {
            return exitCode;
        }
    }
    public enum Source { MODEL, HUMAN, RULE }
    public record DatasetSource(String datasetId, String datasetVersion, DataModel.SourceType sourceType) {
        public DatasetSource {
            Checks.id(datasetId);
            Checks.id(datasetVersion);
            Checks.require(sourceType != null);
        }
    }
    public record ExecutionScope(DataModel.InvocationOrigin authorizedRetrieval, DataModel.InvocationOrigin testedModel,
                                 DataModel.InvocationOrigin judgeModel) {
        public ExecutionScope {
            Checks.require(authorizedRetrieval != null && testedModel != null && judgeModel != null);
        }
    }
    public record Identity(String datasetSha256, String corpusSnapshotId, DataModel.ClaimState corpusState,
                           String aclSnapshotId, DataModel.ClaimState aclState, Map<String, Long> identityBindings,
                           String judgeConfigurationSha256, String rubricSha256, String policySha256, List<String> caseIds,
                           DatasetSource dataset) {
        public Identity {
            Checks.require(dataset != null);
            identityBindings = Map.copyOf(identityBindings);
            caseIds = List.copyOf(caseIds);
        }
    }
    public record Row(String caseId, List<String> tags, DataModel.ExecutionStatus execution,
                      JudgmentModel.Status judgment, Metrics.Scores initial, Metrics.Scores effective,
                      Source source, Map<String, Source> claimSources, boolean required, boolean reviewed, boolean labelError) {
    }
    public record Group(String tag, Map<String, Metrics.Aggregate> initial, Map<String, Metrics.Aggregate> effective) {
    }
    public record Failure(String tag, String metric, String reason) {
    }
    public record Regression(String caseId, String metric, double baseline, double candidate) {
    }
    public record ModelComparison(boolean declaredSame, int observedSame, int observedDifferent, int unobserved) {
    }
    public record Report(int schemaVersion, Identity identity, String collectionSha256, String judgmentSha256,
                          String reviewHistorySha256, ModelComparison modelComparison, ExecutionScope executionScope,
                          ReportConfig config, List<String> requiredCases, String requiredSha256,
                         List<Row> cases, List<Group> groups, List<Failure> knownFailures, List<Regression> regressions,
                         boolean complete, Status status, int exitCode) {
    }

    public Report read(String file) {
        if (file == null) {
            return null;
        }
        var path = LocalFiles.path(file);
        var bundle = new BundleFiles(path.getParent());
        var report = StrictJson.read(bundle.read(path.getFileName().toString(), StrictJson.FILE_LIMIT).bytes(), Report.class);
        if (report.schemaVersion() != 1 || report.identity() == null || report.status() == null || report.executionScope() == null
                || report.status().exitCode() != report.exitCode() || report.cases() == null || report.cases().size() > StrictJson.CASE_LIMIT
                || !report.identity().caseIds().equals(report.cases().stream().map(Row::caseId).sorted().toList())
                || new HashSet<>(report.identity().caseIds()).size() != report.cases().size()
                || !report.config().policySha256().equals(report.identity().policySha256())) {
            throw invalid();
        }
        for (var row : report.cases()) {
            Checks.id(row.caseId());
            Checks.unique(row.tags());
            row.tags().forEach(Checks::id);
            Checks.require(row.execution() != null && row.judgment() != null && row.source() != null);
            validateScores(row.initial());
            validateScores(row.effective());
        }
        // 重新计算统计，基线不能利用汇总中的伪造均值通过比较。
        if (!report.groups().equals(groups(report.cases())) || !report.cases().stream().map(Row::caseId).toList().equals(report.identity().caseIds())) {
            throw invalid();
        }
        return report;
    }

    public Report calculate(DatasetReader.Batch dataset, CollectionReader.Run collection, JudgmentReader.Run judgments,
                            Reviews.History history, ReportConfig config, Report baseline) {
        var required = new Reviews().required(dataset, collection, judgments, config).stream().sorted().toList();
        var origin = collection.manifest().invocationOrigin();
        boolean noModel = collection.manifest().complete() && collection.answers().values().stream()
                .allMatch(answer -> answer.answerKind() == DataModel.AnswerKind.NO_RESULTS);
        var scope = new ExecutionScope(origin, noModel ? DataModel.InvocationOrigin.NOT_CALLED : origin,
                judgments.manifest().invocationOrigin());
        var identity = new Identity(dataset.datasetSha256(), collection.manifest().corpusSnapshotId(), collection.manifest().corpusState(),
                collection.manifest().aclSnapshotId(), collection.manifest().aclState(), collection.manifest().identityBindings(),
                judgments.manifest().configurationSha256(), judgments.manifest().rubricSha256(), config.policySha256(),
                dataset.cases().stream().map(DataModel.Case::caseId).sorted().toList(),
                new DatasetSource(dataset.dataset().datasetId(), dataset.dataset().datasetVersion(), dataset.dataset().sourceType()));
        List<Row> rows = new ArrayList<>();
        for (var item : dataset.cases().stream().sorted(Comparator.comparing(DataModel.Case::caseId)).toList()) {
            String id = item.caseId();
            var original = judgments.rows().get(id);
            var effective = Reviews.effective(original, history);
            var review = history.latest().get(id);
            var answer = collection.answers().get(id);
            var input = collection.inputs().get(id);
            boolean reviewed = review != null && review.decision() == ReviewModel.Decision.CONFIRMED;
            Source source = reviewed ? Source.HUMAN : answer.answerKind() == DataModel.AnswerKind.NO_RESULTS ? Source.RULE : Source.MODEL;
            Map<String, Source> claims = new TreeMap<>();
            if (effective.grounding() != null) {
                effective.grounding().claims().forEach(claim -> claims.put(claim.claimId(), source));
            }
            rows.add(new Row(id, item.tags().stream().sorted().toList(), answer.status(), original.status(),
                    Metrics.calculate(dataset.labels().get(id), answer, input, original),
                    Metrics.calculate(dataset.labels().get(id), answer, input, effective), source, claims, required.contains(id), reviewed,
                    review != null && review.decision() == ReviewModel.Decision.LABEL_ERROR));
        }
        boolean complete = collection.manifest().complete() && judgments.manifest().complete() && rows.stream().noneMatch(row ->
                row.effective().values().values().stream().anyMatch(value -> value.state() == Metrics.State.NOT_EXERCISED));
        List<Group> groups = groups(rows);
        if (baseline != null && (!identity.equals(baseline.identity()) || !compatible(scope.authorizedRetrieval(), baseline.executionScope().authorizedRetrieval())
                || !compatible(scope.judgeModel(), baseline.executionScope().judgeModel()) || !complete || !baseline.complete()
                || baseline.status() == Status.INCOMPLETE || baseline.status() == Status.REVIEW_REQUIRED)) {
            throw invalid();
        }
        List<Failure> failures = new ArrayList<>();
        for (var rule : config.rules()) {
            var group = groups.stream().filter(candidate -> java.util.Objects.equals(candidate.tag(), rule.tag())).findFirst().orElseThrow(Reports::invalid);
            var value = group.effective().get(rule.metric());
            if (value.applicable() == 0 || rule.maxRegression() != null && baseline == null) {
                throw invalid();
            }
            if (rule.min() != null && value.mean() != null && value.mean() + TOLERANCE < rule.min()) {
                failures.add(new Failure(rule.tag(), rule.metric(), "BELOW_MIN"));
            }
            if (rule.maxRegression() != null) {
                var prior = baseline.groups().stream().filter(candidate -> java.util.Objects.equals(candidate.tag(), rule.tag()))
                        .findFirst().orElseThrow(Reports::invalid).effective().get(rule.metric());
                if (prior == null || prior.applicable() == 0 || prior.mean() == null || prior.unknown() > 0) {
                    throw invalid();
                }
                if (value.mean() != null && prior.mean() - value.mean() > rule.maxRegression() + TOLERANCE) {
                    failures.add(new Failure(rule.tag(), rule.metric(), "REGRESSION"));
                }
            }
        }
        if (rows.stream().anyMatch(row -> row.effective().criticalErrors() > 0)) {
            failures.add(new Failure(null, null, "CRITICAL_ERROR"));
        }
        failures.sort(Comparator.comparing((Failure failure) -> String.valueOf(failure.tag()))
                .thenComparing(failure -> String.valueOf(failure.metric())).thenComparing(Failure::reason));
        boolean needsReview = rows.stream().anyMatch(row -> row.required() && !row.reviewed() || row.labelError()
                || row.effective().values().values().stream().anyMatch(value -> value.state() == Metrics.State.UNKNOWN))
                || judgments.rows().values().stream().anyMatch(row -> Reviews.semanticConflict(Reviews.effective(row, history)));
        Status status = !complete ? Status.INCOMPLETE : needsReview ? Status.REVIEW_REQUIRED : !failures.isEmpty() ? Status.FAIL
                : config.rules().isEmpty() ? Status.NOT_CONFIGURED : Status.PASS;
        List<Regression> regressions = new ArrayList<>();
        if (baseline != null) {
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                var prior = baseline.cases().get(index);
                for (String metric : Metrics.NAMES) {
                    var oldValue = prior.effective().values().get(metric);
                    var newValue = row.effective().values().get(metric);
                    if (oldValue.state() == Metrics.State.KNOWN && newValue.state() == Metrics.State.KNOWN
                            && oldValue.value() - newValue.value() > TOLERANCE) {
                        regressions.add(new Regression(row.caseId(), metric, oldValue.value(), newValue.value()));
                    }
                }
            }
        }
        int observedSame = (int) collection.inputs().values().stream().filter(input -> judgments.manifest().model().equals(input.model())).count();
        int observedDifferent = (int) collection.inputs().values().stream().filter(input -> input.model() != null
                && !judgments.manifest().model().equals(input.model())).count();
        var comparison = new ModelComparison(collection.manifest().model().equals(judgments.manifest().model()),
                observedSame, observedDifferent, rows.size() - observedSame - observedDifferent);
        return new Report(1, identity, collection.sha256(), judgments.sha256(), StrictJson.sha(StrictJson.jsonl(history.revisions())), comparison, scope, config,
                required, StrictJson.sha(StrictJson.bytes(required)), rows, groups, failures, regressions, complete, status, status.exitCode());
    }

    public int publish(Report report, OutputPublisher.Target output) {
        try (var session = new OutputPublisher().begin(output)) {
            session.put("report.json", StrictJson.bytes(report));
            session.put("report.csv", csv(report).getBytes(StandardCharsets.UTF_8));
            session.put("report.md", markdown(report).getBytes(StandardCharsets.UTF_8));
            session.finish();
        }
        return report.exitCode();
    }

    private static boolean compatible(DataModel.InvocationOrigin candidate, DataModel.InvocationOrigin baseline) {
        // 合法 NO_RESULTS 无需调用模型/裁判，不因此禁止同一配置下的质量退步比较。
        return candidate == baseline || candidate == DataModel.InvocationOrigin.NOT_CALLED || baseline == DataModel.InvocationOrigin.NOT_CALLED;
    }

    private static List<Group> groups(List<Row> rows) {
        List<Group> groups = new ArrayList<>();
        groups.add(group(null, rows));
        TreeSet<String> tags = new TreeSet<>();
        rows.forEach(row -> tags.addAll(row.tags()));
        tags.forEach(tag -> groups.add(group(tag, rows.stream().filter(row -> row.tags().contains(tag)).toList())));
        return groups;
    }

    private static Group group(String tag, List<Row> rows) {
        Map<String, Metrics.Aggregate> initial = new TreeMap<>();
        Map<String, Metrics.Aggregate> effective = new TreeMap<>();
        Metrics.NAMES.forEach(name -> {
            initial.put(name, Metrics.aggregate(rows.stream().map(Row::initial).toList(), name));
            effective.put(name, Metrics.aggregate(rows.stream().map(Row::effective).toList(), name));
        });
        return new Group(tag, initial, effective);
    }

    private static String csv(Report report) {
        StringBuilder output = new StringBuilder("caseId,metric,initialState,initial,effectiveState,effective,source,required,reviewed,initialCriticalErrors,effectiveCriticalErrors,execution,judgment,regressionBaseline,regressionAmount,datasetId,datasetVersion,sourceType,authorizedRetrieval,testedModel,judgeModel\n");
        var dataset = report.identity().dataset();
        var scope = report.executionScope();
        for (var row : report.cases()) {
            for (String metric : Metrics.NAMES) {
                var initial = row.initial().values().get(metric);
                var effective = row.effective().values().get(metric);
                var regression = report.regressions().stream().filter(value -> value.caseId().equals(row.caseId()) && value.metric().equals(metric))
                        .findFirst().orElse(null);
                output.append(row.caseId()).append(',').append(metric).append(',').append(initial.state()).append(',')
                        .append(initial.value() == null ? "" : initial.value()).append(',').append(effective.state()).append(',')
                        .append(effective.value() == null ? "" : effective.value()).append(',').append(row.source()).append(',')
                        .append(row.required()).append(',').append(row.reviewed()).append(',').append(row.initial().criticalErrors()).append(',')
                        .append(row.effective().criticalErrors()).append(',').append(row.execution()).append(',').append(row.judgment()).append(',')
                        .append(regression == null ? "" : regression.baseline()).append(',')
                        .append(regression == null ? "" : regression.baseline() - regression.candidate()).append(',')
                        .append(dataset.datasetId()).append(',').append(dataset.datasetVersion()).append(',').append(dataset.sourceType()).append(',')
                        .append(scope.authorizedRetrieval()).append(',').append(scope.testedModel()).append(',').append(scope.judgeModel()).append('\n');
            }
        }
        return output.toString();
    }

    private static String markdown(Report report) {
        StringBuilder output = new StringBuilder("状态：" + report.status() + "；退出码：" + report.exitCode()
                + "\n\n|datasetId|datasetVersion|sourceType|authorizedRetrieval|testedModel|judgeModel|\n|---|---|---|---|---|---|\n|"
                + report.identity().dataset().datasetId() + "|" + report.identity().dataset().datasetVersion() + "|"
                + report.identity().dataset().sourceType() + "|" + report.executionScope().authorizedRetrieval() + "|"
                + report.executionScope().testedModel() + "|" + report.executionScope().judgeModel() + "|\n"
                + "\n调用范围：TEST_DOUBLE 为测试替身，HTTP_UNVERIFIED 为经 HTTP 调用但真实后端未独立核验，"
                + "UNKNOWN 为缺少来源记录，NOT_CALLED 为未调用。PASS 仅适用于表中数据与执行范围。\n"
                + "\n模型名称比较：声明同名=" + report.modelComparison().declaredSame() + "，观测同名=" + report.modelComparison().observedSame()
                + "，观测异名=" + report.modelComparison().observedDifferent() + "，未观测=" + report.modelComparison().unobserved()
                + "\n\n|分类|指标|初评|有效结果|已判定/适用/全部|\n|---|---|---|---|---|\n");
        for (var group : report.groups()) {
            for (String name : Metrics.NAMES) {
                var value = group.effective().get(name);
                output.append('|').append(group.tag() == null ? "ALL" : group.tag()).append('|').append(name).append('|')
                        .append(group.initial().get(name).mean()).append('|').append(value.mean()).append('|')
                        .append(value.evaluated()).append('/').append(value.applicable()).append('/').append(value.total()).append("|\n");
            }
        }
        output.append("\n|用例|来源|必需复核|已复核|初评关键错误|有效关键错误|执行|初评状态|\n|---|---|---|---|---|---|---|---|\n");
        report.cases().forEach(row -> output.append('|').append(row.caseId()).append('|').append(row.source()).append('|')
                .append(row.required()).append('|').append(row.reviewed()).append('|').append(row.initial().criticalErrors()).append('|')
                .append(row.effective().criticalErrors()).append('|').append(row.execution()).append('|').append(row.judgment()).append("|\n"));
        output.append("\n|已知失败分类|指标|原因|\n|---|---|---|\n");
        report.knownFailures().forEach(failure -> output.append('|').append(failure.tag() == null ? "ALL" : failure.tag()).append('|')
                .append(failure.metric() == null ? "N/A" : failure.metric()).append('|').append(failure.reason()).append("|\n"));
        output.append("\n|退步用例|指标|基线|候选|\n|---|---|---|---|\n");
        report.regressions().forEach(row -> output.append('|').append(row.caseId()).append('|').append(row.metric()).append('|')
                .append(row.baseline()).append('|').append(row.candidate()).append("|\n"));
        return output.toString();
    }

    private static void validateScores(Metrics.Scores scores) {
        if (scores == null || !scores.values().keySet().equals(new HashSet<>(Metrics.NAMES)) || scores.criticalErrors() < 0) {
            throw invalid();
        }
        for (var value : scores.values().values()) {
            if (value == null || value.state() == null || value.numerator() < 0 || value.denominator() < value.numerator()
                    || value.state() == Metrics.State.KNOWN && (value.value() == null || value.denominator() == 0
                    || !Double.isFinite(value.value()) || Math.abs(value.value() - (double) value.numerator() / value.denominator()) > TOLERANCE)
                    || value.state() != Metrics.State.KNOWN && value.value() != null) {
                throw invalid();
            }
        }
    }

    private static EvaluationFailure invalid() {
        return new EvaluationFailure(EvaluationFailure.Code.CONFIGURATION);
    }
}
