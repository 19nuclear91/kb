package com.zhishiku.kb.answereval;

import java.util.List;

public final class ReviewModel {
    private ReviewModel() {
    }

    public enum Decision { PENDING, CONFIRMED, LABEL_ERROR }
    public record Binding(JudgmentModel.Binding evaluation, String judgeRunId, String judgmentSha256) {
        public Binding {
            Checks.require(evaluation != null);
            Checks.uuid(judgeRunId);
            Checks.sha(judgmentSha256);
        }
    }

    public record Review(int schemaVersion, String reviewId, int revision, String previousRevisionSha256,
                         Binding binding, String reviewerId, Decision decision, String reason,
                         JudgmentModel.Correctness correctness, JudgmentModel.Grounding grounding) {
        public Review {
            Checks.require(schemaVersion == 1 && revision > 0 && revision <= 10_000 && binding != null && decision != null);
            Checks.uuid(reviewId);
            Checks.id(reviewerId);
            if (previousRevisionSha256 != null) {
                Checks.sha(previousRevisionSha256);
            }
            if (decision != Decision.PENDING) {
                Checks.text(reason, 8000);
            }
            Checks.require(correctness != null && grounding != null);
        }

        public String sha256() {
            return StrictJson.sha(StrictJson.bytes(this));
        }
    }

    public record Manifest(int schemaVersion, String datasetSha256, String collectionSha256,
                           String judgmentSha256, DataModel.FileRef revisions, int revisionCount) {
        public Manifest {
            Checks.require(schemaVersion == 1 && revisions != null && revisionCount >= 0 && revisionCount <= 10_000);
            Checks.sha(datasetSha256);
            Checks.sha(collectionSha256);
            Checks.sha(judgmentSha256);
        }
    }

    public record Detail(String caseId, String question, String answer, List<DataModel.Material> materials,
                         JudgmentModel.Judgment initial, Binding binding, boolean required) {
    }
}
