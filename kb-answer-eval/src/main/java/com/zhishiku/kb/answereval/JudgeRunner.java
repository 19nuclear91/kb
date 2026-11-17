package com.zhishiku.kb.answereval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** 全批输入已验证后执行初评；首个调用失败后保留其余未评项。 */
public final class JudgeRunner {
    public int judge(DatasetReader.Batch dataset, CollectionReader.Run collection, JudgeConfig config,
                     String key, Transport transport, LongSupplier clock, OutputPublisher.Target output) {
        dataset.requireTarget(config.targetId(), true);
        var client = new JudgeClient(config, key, transport, clock);
        String runId = UUID.randomUUID().toString();
        String configuration = StrictJson.sha(StrictJson.bytes(config));
        List<JudgmentModel.Judgment> rows = new ArrayList<>();
        boolean stopped = false;
        boolean interrupted = false;
        long written = 0;
        for (var item : dataset.cases()) {
            var answer = collection.answers().get(item.caseId());
            var binding = binding(dataset, collection, item.caseId(), configuration);
            var status = JudgmentModel.Status.COMPLETE;
            JudgmentModel.Correctness correctness = null;
            JudgmentModel.Grounding grounding = null;
            EvaluationFailure.Code error = null;
            int start = client.submitted();
            if (answer.status() != DataModel.ExecutionStatus.COMPLETE) {
                status = JudgmentModel.Status.EXECUTION_INCOMPLETE;
                error = EvaluationFailure.Code.INCOMPLETE;
            } else if (stopped) {
                status = JudgmentModel.Status.NOT_EVALUATED;
                error = EvaluationFailure.Code.INCOMPLETE;
            } else {
                var label = dataset.labels().get(item.caseId());
                var input = collection.inputs().get(item.caseId());
                try {
                    if (answer.answerKind() == DataModel.AnswerKind.NO_RESULTS) {
                        correctness = noResults(label);
                        grounding = new JudgmentModel.Grounding(List.of(), List.of(), true);
                    } else {
                        correctness = client.assess("correctness", JudgeViews.messages("correctness", item, label, answer.answer(), input.materials()), JudgmentModel.Correctness.class);
                        grounding = client.assess("grounding", JudgeViews.messages("grounding", item, label, answer.answer(), input.materials()), JudgmentModel.Grounding.class);
                    }
                    JudgmentRules.validate(label, answer.answer(), input.materials(), correctness, grounding);
                } catch (EvaluationFailure failure) {
                    stopped = true;
                    status = JudgmentModel.Status.JUDGE_FAILED;
                    error = failure.code();
                    correctness = null;
                    grounding = null;
                    interrupted |= Thread.interrupted();
                }
            }
            var row = new JudgmentModel.Judgment(1, runId, binding, status, correctness, grounding, client.callsSince(start), error);
            if (StrictJson.bytes(row).length > StrictJson.LINE_LIMIT) {
                stopped = true;
                row = new JudgmentModel.Judgment(1, runId, binding, JudgmentModel.Status.JUDGE_FAILED, null, null,
                        client.callsSince(start), EvaluationFailure.Code.LIMIT);
            }
            written += StrictJson.bytes(row).length + 1L;
            stopped |= written > StrictJson.FILE_LIMIT - StrictJson.LINE_LIMIT - 4096L * dataset.cases().size();
            rows.add(row);
        }
        boolean complete = rows.stream().allMatch(row -> row.status() == JudgmentModel.Status.COMPLETE);
        try (var session = new OutputPublisher().begin(output)) {
            session.put("configuration.json", StrictJson.bytes(config));
            var reference = session.put("judgments.jsonl", StrictJson.jsonl(rows));
            session.put("judgment.json", StrictJson.bytes(new JudgmentModel.Manifest(1, runId, dataset.datasetSha256(),
                    collection.sha256(), configuration, config.model(), JudgeViews.VERSION, JudgeViews.rubricSha256(),
                    dataset.cases().stream().map(DataModel.Case::caseId).toList(), reference, client.submitted(), client.estimatedTokens(), complete,
                    client.submitted() == 0 ? DataModel.InvocationOrigin.NOT_CALLED : transport.invocationOrigin())));
            session.finish();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return complete ? 0 : 3;
    }

    public static JudgmentModel.Binding binding(DatasetReader.Batch dataset, CollectionReader.Run collection,
                                                String caseId, String configuration) {
        return new JudgmentModel.Binding(dataset.datasetSha256(), collection.sha256(), collection.manifest().runId(), caseId,
                collection.answers().get(caseId).persistedAnswerSha256(), collection.inputDigests().get(caseId),
                JudgeViews.VERSION, JudgeViews.rubricSha256(), configuration);
    }

    public static JudgmentModel.Correctness noResults(DataModel.Label label) {
        return new JudgmentModel.Correctness(label.facts().stream().map(fact -> new JudgmentModel.Fact(fact.factId(),
                JudgmentModel.FactVerdict.MISSING, "NO_RESULTS")).toList(), JudgmentModel.Behavior.ABSTAIN, List.of());
    }
}
