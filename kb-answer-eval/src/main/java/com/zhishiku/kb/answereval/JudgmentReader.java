package com.zhishiku.kb.answereval;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JudgmentReader {
    public record Run(JudgmentModel.Manifest manifest, String sha256, Map<String, JudgmentModel.Judgment> rows) {
        public Run {
            rows = Map.copyOf(rows);
        }
    }

    public Run read(String file, DatasetReader.Batch dataset, CollectionReader.Run collection) {
        var path = LocalFiles.path(file);
        var bundle = new BundleFiles(path.getParent());
        var bytes = bundle.read(path.getFileName().toString(), StrictJson.MANIFEST_LIMIT);
        var manifest = StrictJson.read(bytes.bytes(), JudgmentModel.Manifest.class);
        var configuration = bundle.read("configuration.json", StrictJson.MANIFEST_LIMIT);
        var config = StrictJson.read(configuration.bytes(), JudgeConfig.class);
        dataset.requireTarget(config.targetId(), true);
        var expected = new HashSet<>(dataset.cases().stream().map(DataModel.Case::caseId).toList());
        if (!manifest.datasetSha256().equals(dataset.datasetSha256()) || !manifest.collectionSha256().equals(collection.sha256())
                || !new HashSet<>(manifest.caseIds()).equals(expected) || !JudgeViews.VERSION.equals(manifest.rubricVersion())
                || !JudgeViews.rubricSha256().equals(manifest.rubricSha256())
                || !configuration.sha256().equals(manifest.configurationSha256()) || !config.model().equals(manifest.model())
                || !config.rubricVersion().equals(manifest.rubricVersion()) || manifest.submittedCalls() > config.maxRequests()
                || manifest.estimatedInputTokens() > config.maxEstimatedInputTokens()) {
            throw binding();
        }
        List<JudgmentModel.Judgment> rows = StrictJson.lines(bundle.read(manifest.judgments(), StrictJson.FILE_LIMIT).bytes(),
                JudgmentModel.Judgment.class, StrictJson.CASE_LIMIT);
        Map<String, JudgmentModel.Judgment> mapped = new LinkedHashMap<>();
        int submitted = 0;
        for (var row : rows) {
            String id = row.binding().caseId();
            if (!expected.contains(id) || mapped.putIfAbsent(id, row) != null || !row.judgeRunId().equals(manifest.judgeRunId())
                    || !row.binding().equals(JudgeRunner.binding(dataset, collection, id, manifest.configurationSha256()))) {
                throw binding();
            }
            submitted += row.calls().size();
            if (row.status() == JudgmentModel.Status.COMPLETE) {
                var answer = collection.answers().get(id);
                if (answer.status() != DataModel.ExecutionStatus.COMPLETE || row.calls().stream().anyMatch(call -> !call.stage().equals("COMPLETE"))) {
                    throw binding();
                }
                JudgmentRules.validate(dataset.labels().get(id), answer.answer(), collection.inputs().get(id).materials(), row.correctness(), row.grounding());
                if (answer.answerKind() == DataModel.AnswerKind.NO_RESULTS) {
                    if (!row.calls().isEmpty() || !row.correctness().equals(JudgeRunner.noResults(dataset.labels().get(id)))
                            || !row.grounding().equals(new JudgmentModel.Grounding(List.of(), List.of(), true))) {
                        throw binding();
                    }
                } else if (!row.calls().stream().map(JudgmentModel.Call::view).toList().equals(List.of("correctness", "grounding"))) {
                    throw binding();
                }
            } else if (row.correctness() != null || row.grounding() != null || row.error() == null) {
                throw binding();
            }
        }
        if (!mapped.keySet().equals(expected) || submitted != manifest.submittedCalls()
                || manifest.complete() != rows.stream().allMatch(row -> row.status() == JudgmentModel.Status.COMPLETE)) {
            throw binding();
        }
        return new Run(manifest, bytes.sha256(), mapped);
    }

    private static EvaluationFailure binding() {
        return new EvaluationFailure(EvaluationFailure.Code.BINDING);
    }
}
