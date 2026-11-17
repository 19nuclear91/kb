package com.zhishiku.kb.answereval;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 复核文件是修订日志；每例连续前驱链，重复幂等，分叉拒绝。 */
public final class Reviews {
    public record History(List<ReviewModel.Review> revisions, Map<String, ReviewModel.Review> latest) {
        public History {
            revisions = List.copyOf(revisions);
            latest = Map.copyOf(latest);
        }

        public static History empty() {
            return new History(List.of(), Map.of());
        }
    }

    public static ReviewModel.Binding binding(JudgmentModel.Judgment judgment) {
        return new ReviewModel.Binding(judgment.binding(), judgment.judgeRunId(), StrictJson.sha(StrictJson.bytes(judgment)));
    }

    public Set<String> required(DatasetReader.Batch dataset, CollectionReader.Run collection,
                                JudgmentReader.Run judgments, ReportConfig config) {
        Set<String> required = new TreeSet<>();
        Map<String, List<String>> passingByTag = new HashMap<>();
        for (var item : dataset.cases()) {
            var scores = Metrics.calculate(dataset.labels().get(item.caseId()), collection.answers().get(item.caseId()),
                    collection.inputs().get(item.caseId()), judgments.rows().get(item.caseId()));
            if (!Metrics.ideal(scores) || semanticConflict(judgments.rows().get(item.caseId()))) {
                required.add(item.caseId());
            } else {
                // 无标签用例也必须有可复现抽查机会。
                List<String> tags = item.tags().isEmpty() ? List.of("__untagged__") : item.tags();
                tags.forEach(tag -> passingByTag.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(item.caseId()));
            }
        }
        Comparator<String> rank = Comparator.<String, String>comparing(id -> StrictJson.textSha(dataset.datasetSha256()
                + "|" + JudgeViews.VERSION + "|" + id)).thenComparing(Comparator.naturalOrder());
        passingByTag.values().forEach(ids -> {
            ids.sort(rank);
            required.addAll(ids.subList(0, Math.max(1, (int) Math.ceil(config.reviewPolicy().sampleProportion() * ids.size()))));
        });
        return java.util.Collections.unmodifiableSet(required);
    }

    public void template(DatasetReader.Batch dataset, CollectionReader.Run collection, JudgmentReader.Run judgments,
                         ReportConfig config, OutputPublisher.Target output) {
        Set<String> required = required(dataset, collection, judgments, config);
        List<ReviewModel.Detail> details = new ArrayList<>();
        List<ReviewModel.Review> drafts = new ArrayList<>();
        for (var item : dataset.cases().stream().sorted(Comparator.comparing(DataModel.Case::caseId)).toList()) {
            var original = judgments.rows().get(item.caseId());
            var answer = collection.answers().get(item.caseId());
            var input = collection.inputs().get(item.caseId());
            var binding = binding(original);
            details.add(new ReviewModel.Detail(item.caseId(), item.question(), answer.answer(), input == null ? List.of() : input.materials(),
                    original, binding, required.contains(item.caseId())));
            if (original.status() == JudgmentModel.Status.COMPLETE && required.contains(item.caseId())) {
                String id = UUID.nameUUIDFromBytes((binding.judgmentSha256() + "|1").getBytes(StandardCharsets.UTF_8)).toString();
                drafts.add(new ReviewModel.Review(1, id, 1, null, binding, "REPLACE_REVIEWER", ReviewModel.Decision.PENDING,
                        null, original.correctness(), original.grounding()));
            }
        }
        try (var session = new OutputPublisher().begin(output)) {
            for (var detail : details) {
                session.put("details/" + StrictJson.textSha(detail.caseId()) + ".json", StrictJson.bytes(detail));
            }
            session.put("reviews.jsonl", StrictJson.jsonl(drafts));
            session.put("policy.json", StrictJson.bytes(Map.of("policy", config.reviewPolicy(), "required", required,
                    "requiredSha256", StrictJson.sha(StrictJson.bytes(required)))));
            session.finish();
        }
    }

    public History read(String file, DatasetReader.Batch dataset, CollectionReader.Run collection, JudgmentReader.Run judgments) {
        if (file == null) {
            return History.empty();
        }
        var path = LocalFiles.path(file);
        var bundle = new BundleFiles(path.getParent());
        var manifest = StrictJson.read(bundle.read(path.getFileName().toString(), StrictJson.MANIFEST_LIMIT).bytes(), ReviewModel.Manifest.class);
        if (!manifest.datasetSha256().equals(dataset.datasetSha256()) || !manifest.collectionSha256().equals(collection.sha256())
                || !manifest.judgmentSha256().equals(judgments.sha256())) {
            throw conflict();
        }
        var revisions = StrictJson.lines(bundle.read(manifest.revisions(), StrictJson.FILE_LIMIT).bytes(), ReviewModel.Review.class, 10_000);
        var result = merge(dataset, collection, judgments, History.empty(), revisions);
        if (result.revisions().size() != manifest.revisionCount()) {
            throw conflict();
        }
        return result;
    }

    public History merge(DatasetReader.Batch dataset, CollectionReader.Run collection, JudgmentReader.Run judgments,
                         History history, List<ReviewModel.Review> incoming) {
        Map<String, ReviewModel.Review> unique = new LinkedHashMap<>();
        List<ReviewModel.Review> combined = new ArrayList<>(history.revisions());
        combined.addAll(incoming);
        if (combined.size() > 20_000) {
            throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
        }
        for (var review : combined) {
            var prior = unique.putIfAbsent(review.reviewId(), review);
            if (prior != null && !prior.equals(review)) {
                throw conflict();
            }
        }
        if (unique.size() > 10_000) {
            throw new EvaluationFailure(EvaluationFailure.Code.LIMIT);
        }
        List<ReviewModel.Review> sorted = unique.values().stream().sorted(Comparator
                .comparing((ReviewModel.Review review) -> review.binding().evaluation().caseId()).thenComparingInt(ReviewModel.Review::revision)).toList();
        Map<String, ReviewModel.Review> latest = new HashMap<>();
        for (var review : sorted) {
            String caseId = review.binding().evaluation().caseId();
            var original = judgments.rows().get(caseId);
            var prior = latest.get(caseId);
            if (original == null || !review.binding().equals(binding(original)) || review.decision() == ReviewModel.Decision.PENDING
                    || original.status() != JudgmentModel.Status.COMPLETE || review.revision() != (prior == null ? 1 : prior.revision() + 1)
                    || !java.util.Objects.equals(review.previousRevisionSha256(), prior == null ? null : prior.sha256())) {
                throw conflict();
            }
            var answer = collection.answers().get(caseId);
            JudgmentRules.validate(dataset.labels().get(caseId), answer.answer(), collection.inputs().get(caseId).materials(), review.correctness(), review.grounding());
            if (answer.answerKind() == DataModel.AnswerKind.NO_RESULTS
                    && (!review.correctness().equals(original.correctness()) || !review.grounding().equals(original.grounding()))) {
                throw conflict();
            }
            latest.put(caseId, review);
        }
        return new History(sorted, latest);
    }

    public void publish(DatasetReader.Batch dataset, CollectionReader.Run collection, JudgmentReader.Run judgments,
                        History history, OutputPublisher.Target output) {
        try (var session = new OutputPublisher().begin(output)) {
            var reference = session.put("revisions.jsonl", StrictJson.jsonl(history.revisions()));
            session.put("review.json", StrictJson.bytes(new ReviewModel.Manifest(1, dataset.datasetSha256(), collection.sha256(),
                    judgments.sha256(), reference, history.revisions().size())));
            session.finish();
        }
    }

    public static JudgmentModel.Judgment effective(JudgmentModel.Judgment original, History history) {
        var review = history.latest().get(original.binding().caseId());
        if (review == null || review.decision() != ReviewModel.Decision.CONFIRMED) {
            return original;
        }
        return new JudgmentModel.Judgment(original.schemaVersion(), original.judgeRunId(), original.binding(), original.status(),
                review.correctness(), review.grounding(), original.calls(), original.error());
    }

    public static boolean semanticConflict(JudgmentModel.Judgment judgment) {
        return judgment.status() == JudgmentModel.Status.COMPLETE
                && (judgment.correctness().behavior() == JudgmentModel.Behavior.ABSTAIN && !judgment.grounding().claims().isEmpty()
                || judgment.grounding().noVerifiableClaims() && judgment.correctness().facts().stream()
                .anyMatch(fact -> fact.verdict() == JudgmentModel.FactVerdict.COVERED));
    }

    private static EvaluationFailure conflict() {
        return new EvaluationFailure(EvaluationFailure.Code.REVIEW_CONFLICT);
    }
}
