package com.zhishiku.kb.answereval;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 裁判前完整核对全部用例，不能先上传合法前缀再发现后部绑定损坏。 */
public final class CollectionReader {
    public record Run(DataModel.Collection manifest, String sha256, Map<String, DataModel.Answer> answers,
                      Map<String, DataModel.InputSnapshot> inputs, Map<String, String> inputDigests) {
        public Run {
            answers = Map.copyOf(answers);
            inputs = Map.copyOf(inputs);
            inputDigests = Map.copyOf(inputDigests);
        }

        @Override
        public String toString() {
            return "Run[sha256=" + sha256 + "]";
        }
    }

    public Run read(String file, DatasetReader.Batch dataset) {
        Path path = LocalFiles.path(file);
        var bundle = new BundleFiles(path.getParent());
        var bytes = bundle.read(path.getFileName().toString(), StrictJson.MANIFEST_LIMIT);
        var manifest = StrictJson.read(bytes.bytes(), DataModel.Collection.class);
        Set<String> expected = new HashSet<>(dataset.cases().stream().map(DataModel.Case::caseId).toList());
        if (!manifest.datasetSha256().equals(dataset.datasetSha256()) || !new HashSet<>(manifest.caseIds()).equals(expected)
                || !manifest.corpusSnapshotId().equals(dataset.dataset().corpusSnapshotId())
                || manifest.sourceType() != dataset.dataset().sourceType()) {
            throw binding();
        }
        List<DataModel.Answer> answers = StrictJson.lines(bundle.read(manifest.answers(), StrictJson.FILE_LIMIT).bytes(),
                DataModel.Answer.class, StrictJson.CASE_LIMIT);
        Map<String, DataModel.Answer> answerMap = new HashMap<>();
        Map<String, DataModel.InputSnapshot> inputs = new HashMap<>();
        Map<String, String> digests = new HashMap<>();
        Set<Long> conversations = new HashSet<>();
        Set<String> attempts = new HashSet<>();
        Set<String> requests = new HashSet<>();
        long readBytes = bytes.bytes().length;
        for (DataModel.Answer answer : answers) {
            if (answerMap.putIfAbsent(answer.caseId(), answer) != null || !expected.contains(answer.caseId())) {
                throw binding();
            }
            if (answer.attempt() != null && (!attempts.add(answer.attempt().attemptId()) || !requests.add(answer.attempt().requestId())
                    || answer.attempt().conversationId() != null && !conversations.add(answer.attempt().conversationId()))) {
                throw binding();
            }
            if (answer.status() != DataModel.ExecutionStatus.COMPLETE) {
                if (answer.answer() != null || answer.persistedAnswerSha256() != null || !answer.sources().isEmpty()
                        || manifest.inputs().containsKey(answer.caseId())) {
                    throw binding();
                }
                continue;
            }
            var reference = manifest.inputs().get(answer.caseId());
            if (reference == null) {
                throw binding();
            }
            var captured = bundle.read(reference, OutputPublisher.CAPTURE_LIMIT);
            readBytes += captured.bytes().length;
            if (readBytes > OutputPublisher.RUN_LIMIT) {
                throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
            }
            var snapshot = StrictJson.read(captured.bytes(), DataModel.InputSnapshot.class);
            var item = dataset.cases().stream().filter(candidate -> candidate.caseId().equals(answer.caseId())).findFirst().orElseThrow();
            verify(dataset, manifest, item, answer, snapshot);
            inputs.put(answer.caseId(), snapshot);
            digests.put(answer.caseId(), captured.sha256());
        }
        if (!answerMap.keySet().equals(expected) || !inputs.keySet().equals(manifest.inputs().keySet())
                || manifest.complete() != (inputs.size() == expected.size())) {
            throw binding();
        }
        return new Run(manifest, bytes.sha256(), answerMap, inputs, digests);
    }

    private static void verify(DatasetReader.Batch dataset, DataModel.Collection manifest, DataModel.Case item,
                               DataModel.Answer answer, DataModel.InputSnapshot snapshot) {
        DataModel.Binding binding = answer.binding();
        Long expectedUser = manifest.identityBindings().get(item.identityAlias());
        if (binding == null || answer.attempt() == null || expectedUser == null || !binding.equals(snapshot.binding())
                || !binding.runId().equals(manifest.runId()) || !binding.caseId().equals(item.caseId())
                || !binding.datasetSha256().equals(dataset.datasetSha256()) || binding.userId() != expectedUser
                || !binding.questionSha256().equals(StrictJson.textSha(item.question()))
                || !binding.attemptId().equals(answer.attempt().attemptId())
                || !Long.valueOf(binding.conversationId()).equals(answer.attempt().conversationId())
                || !binding.turnId().equals(answer.attempt().turnId()) || answer.answer() == null
                || answer.error() != null || !StrictJson.textSha(answer.answer()).equals(answer.persistedAnswerSha256())
                || answer.answerKind() != snapshot.answerKind() || answer.businessStage() != DataModel.BusinessStage.COMPLETED
                || answer.settlementStage() != DataModel.SettlementStage.CONFIRMED
                || snapshot.businessStage() != DataModel.BusinessStage.COMPLETED
                || snapshot.settlementStage() != DataModel.SettlementStage.CONFIRMED
                || !answer.sources().equals(snapshot.sealedSources())) {
            throw binding();
        }
        dataset.requireMaterials(snapshot.materials());
        if (answer.answerKind() == DataModel.AnswerKind.NO_RESULTS) {
            if (!CollectionRunner.NO_RESULTS.equals(answer.answer()) || !snapshot.materials().isEmpty()
                    || !snapshot.messages().isEmpty() || !answer.sources().isEmpty()
                    || snapshot.modelStage() != DataModel.ModelStage.NOT_CALLED || snapshot.requestSha256() != null
                    || snapshot.answerSha256() != null) {
                throw binding();
            }
        } else if (snapshot.modelStage() != DataModel.ModelStage.MODEL_COMPLETED || snapshot.requestSha256() == null
                || snapshot.messages().isEmpty() || !snapshot.messages().getLast().role().equals("user")
                || !snapshot.messages().getLast().content().equals(item.question())
                || !StrictJson.textSha(answer.answer()).equals(snapshot.answerSha256())
                || snapshot.answerCodePoints() != answer.answer().codePointCount(0, answer.answer().length())
                || !snapshot.usage().equals(answer.usage())
                || !snapshot.materials().stream().map(DataModel.Material::source).toList().equals(answer.sources())) {
            throw binding();
        }
    }

    private static EvaluationFailure binding() {
        return new EvaluationFailure(EvaluationFailure.Code.BINDING);
    }
}
