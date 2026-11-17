package com.zhishiku.kb.answereval;

import java.io.ByteArrayInputStream;
import java.net.http.HttpHeaders;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EvaluationFixtures {
    static final String ANSWER = "100 元🧾";

    private EvaluationFixtures() {
    }

    static JudgeConfig config() {
        return new JudgeConfig(1, "judge-config", "judge", "https://judge.invalid/v1", false, "JUDGE_KEY", "judge-model",
                JudgeViews.VERSION, "max_tokens", 32000, 4096, 1000, 1.0, 1_000_000, 2000, null, null, null);
    }

    static DataModel.Material material() {
        return new DataModel.Material(DataFixtures.SOURCE, DataFixtures.CORPUS, false, StrictJson.textSha(DataFixtures.CORPUS));
    }

    static CollectionReader.Run collection(DatasetReader.Batch dataset, Path path, DataModel.AnswerKind kind, boolean complete) {
        List<DataModel.Answer> rows = new ArrayList<>();
        Map<String, DataModel.InputSnapshot> snapshots = new LinkedHashMap<>();
        long conversation = 2;
        for (var item : dataset.cases()) {
            String attemptId = conversation == 2 ? DataFixtures.UUID : UUID.randomUUID().toString();
            String turnId = conversation == 2 ? DataFixtures.UUID : UUID.randomUUID().toString();
            var binding = new DataModel.Binding("run", item.caseId(), attemptId, dataset.datasetSha256(), 1, 0, conversation++,
                    turnId, StrictJson.textSha(item.question()));
            boolean model = kind == DataModel.AnswerKind.MODEL;
            String text = model ? ANSWER : CollectionRunner.NO_RESULTS;
            var usage = new DataModel.Usage(null, null);
            var input = new DataModel.InputSnapshot(1, binding, "instance", model ? "observed" : null, model ? "v1" : null,
                    model ? List.of(new DataModel.Message("user", item.question())) : List.of(),
                    model ? List.of(material()) : List.of(), model ? "a".repeat(64) : null,
                    model ? DataModel.ModelStage.MODEL_COMPLETED : DataModel.ModelStage.NOT_CALLED,
                    model ? StrictJson.textSha(text) : null, model ? text.codePointCount(0, text.length()) : 0, usage, kind,
                    DataModel.BusinessStage.COMPLETED, DataModel.SettlementStage.CONFIRMED, model ? List.of(DataFixtures.SOURCE) : List.of());
            var answer = complete ? new DataModel.Answer(item.caseId(), DataModel.ExecutionStatus.COMPLETE,
                    new DataModel.Attempt(UUID.randomUUID().toString(), attemptId, binding.conversationId(), turnId), binding, kind, text,
                    StrictJson.textSha(text), input.sealedSources(), DataModel.BusinessStage.COMPLETED,
                    DataModel.SettlementStage.CONFIRMED, usage, null)
                    : new DataModel.Answer(item.caseId(), DataModel.ExecutionStatus.NOT_RUN, null, null, null, null, null,
                    List.of(), DataModel.BusinessStage.NOT_CONFIRMED, DataModel.SettlementStage.NOT_CONFIRMED, usage, EvaluationFailure.Code.INCOMPLETE);
            rows.add(answer);
            snapshots.put(item.caseId(), input);
        }
        var publisher = new OutputPublisher();
        try (var session = publisher.begin(publisher.preflight(path.toString()))) {
            Map<String, DataModel.FileRef> inputs = new LinkedHashMap<>();
            if (complete) {
                snapshots.forEach((id, input) -> inputs.put(id, session.put(id.equals("case-1") ? "input.json" : "input-" + id + ".json",
                        StrictJson.bytes(input))));
            }
            var answers = session.put("answers.jsonl", StrictJson.jsonl(rows));
            session.put("collection.json", StrictJson.bytes(new DataModel.Collection(1, "run", dataset.datasetSha256(), "v1", "candidate",
                    "b".repeat(64), "alias", dataset.dataset().corpusSnapshotId(), DataModel.ClaimState.DECLARED_ONLY, "acl",
                    DataModel.ClaimState.DECLARED_ONLY, dataset.dataset().sourceType(), dataset.cases().stream().map(DataModel.Case::caseId).toList(),
                    Map.of("employee", 1L), answers, inputs, complete,
                    complete ? DataModel.InvocationOrigin.TEST_DOUBLE : DataModel.InvocationOrigin.NOT_CALLED)));
            session.finish();
        }
        return new CollectionReader().read(path.resolve("collection.json").toString(), dataset);
    }

    static JudgmentModel.Correctness correctness() {
        return new JudgmentModel.Correctness(List.of(new JudgmentModel.Fact("amount", JudgmentModel.FactVerdict.COVERED, "金额一致")),
                JudgmentModel.Behavior.ANSWER, List.of());
    }

    static JudgmentModel.Grounding grounding() {
        return new JudgmentModel.Grounding(List.of(new JudgmentModel.Claim("amount", new JudgmentModel.Span(0, 5, "100 元"),
                JudgmentModel.ClaimVerdict.SUPPORTED, List.of(new JudgmentModel.Evidence(DataFixtures.SOURCE,
                new JudgmentModel.Span(6, 11, "100 元"))), "材料明确给出")),
                List.of(new JudgmentModel.Material(DataFixtures.SOURCE, JudgmentModel.Relevance.RELEVANT, "相关")), false);
    }

    static JudgmentModel.Judgment judgment(DatasetReader.Batch dataset, CollectionReader.Run collection,
                                          JudgmentModel.Correctness correctness, JudgmentModel.Grounding grounding) {
        return new JudgmentModel.Judgment(1, DataFixtures.UUID, JudgeRunner.binding(dataset, collection, "case-1", "c".repeat(64)),
                JudgmentModel.Status.COMPLETE, correctness, grounding, List.of(), null);
    }

    static Transport goodJudge() {
        return (request, deadline) -> {
            var data = StrictJson.parse(request.body());
            String instruction = data.path("messages").get(0).path("content").asText();
            return response(instruction.contains("仅判断答案正确性") ? correctness() : grounding());
        };
    }

    static Transport.Response response(Object result) {
        return rawResponse(StrictJson.bytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("role", "assistant", "content", new String(StrictJson.bytes(result), java.nio.charset.StandardCharsets.UTF_8)))))));
    }

    static Transport.Response rawResponse(byte[] bytes) {
        return new Transport.Response(200, HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true),
                new ByteArrayInputStream(bytes));
    }
}
