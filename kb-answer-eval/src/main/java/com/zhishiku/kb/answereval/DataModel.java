package com.zhishiku.kb.answereval;

import java.util.List;
import java.util.Map;

/** 回答评测 v1 文件契约，与既有检索评测格式独立。 */
public final class DataModel {
    private DataModel() {
    }

    public enum SourceType { SYNTHETIC, HUMAN_APPROVED_DEIDENTIFIED }
    /** HTTP 只证明采用网络传输，不能独立证明目标内部使用了真实检索或模型。 */
    public enum InvocationOrigin { HTTP_UNVERIFIED, TEST_DOUBLE, UNKNOWN, NOT_CALLED }
    public enum Expected { ANSWER, ABSTAIN }
    public enum ExecutionStatus { COMPLETE, FAILED, CANCELLED, UNKNOWN, NOT_RUN }
    public enum AnswerKind { MODEL, NO_RESULTS }
    public enum ModelStage { NOT_CALLED, INPUT_READY, REQUEST_SUBSCRIBED, MODEL_COMPLETED, MODEL_FAILED, CANCELLED, UNSUPPORTED }
    public enum BusinessStage { NOT_CONFIRMED, COMPLETED, FAILED, CANCELLED }
    public enum SettlementStage { NOT_CONFIRMED, PENDING, CONFIRMED }
    public enum ClaimState { DECLARED_ONLY }
    public enum BehaviorKind { MUST_ABSTAIN, MUST_CONTAIN, MUST_NOT_CONTAIN }

    public record FileRef(String file, String sha256) {
        public FileRef {
            Checks.text(file, 255);
            Checks.sha(sha256);
        }
    }

    public record Approval(String reviewerId, String version, String contentSha256) {
        public Approval {
            Checks.id(reviewerId);
            Checks.id(version);
            Checks.sha(contentSha256);
        }
    }

    public record Dataset(int schemaVersion, String datasetId, String datasetVersion, String corpusSnapshotId,
                          SourceType sourceType, FileRef cases, FileRef labels, FileRef corpus,
                          Approval approval, List<String> allowedTestTargets, List<String> allowedJudgeTargets) {
        public Dataset {
            Checks.require(schemaVersion == 1 && sourceType != null && cases != null && labels != null && corpus != null);
            Checks.id(datasetId);
            Checks.id(datasetVersion);
            Checks.id(corpusSnapshotId);
            allowedTestTargets = identifiers(allowedTestTargets, true);
            allowedJudgeTargets = identifiers(allowedJudgeTargets, true);
        }
    }

    public record Case(String caseId, String identityAlias, String question, List<String> tags, String injectionId) {
        public Case {
            Checks.id(caseId);
            Checks.id(identityAlias);
            Checks.text(question, 8000);
            tags = identifiers(tags, true);
            if (injectionId != null) {
                Checks.id(injectionId);
            }
        }
    }

    public record Fact(String factId, String text) {
        public Fact {
            Checks.id(factId);
            Checks.text(text, 16_000);
        }
    }

    public record Behavior(BehaviorKind kind, String marker) {
        public Behavior {
            Checks.require(kind != null);
            if (kind != BehaviorKind.MUST_ABSTAIN) {
                Checks.text(marker, 8000);
            } else {
                Checks.require(marker == null);
            }
        }
    }

    public record Injection(String injectionId, String attackText, List<Behavior> behaviors) {
        public Injection {
            Checks.id(injectionId);
            Checks.text(attackText, 32_000);
            behaviors = List.copyOf(behaviors);
            Checks.require(!behaviors.isEmpty());
        }
    }

    public record Label(String caseId, Expected expected, List<Fact> facts, List<String> forbiddenAssertions,
                        String abstentionCondition, List<String> criticalErrorCriteria, Injection injection) {
        public Label {
            Checks.id(caseId);
            Checks.require(expected != null && facts != null);
            facts = List.copyOf(facts);
            Checks.unique(facts.stream().map(Fact::factId).toList());
            Checks.require(expected == Expected.ANSWER ? !facts.isEmpty() : facts.isEmpty());
            forbiddenAssertions = texts(forbiddenAssertions);
            criticalErrorCriteria = texts(criticalErrorCriteria);
            if (expected == Expected.ABSTAIN || abstentionCondition != null) {
                Checks.text(abstentionCondition, 16_000);
            }
        }
    }

    public record Source(long docId, long versionId, long chunkId) {
        public Source {
            Checks.require(docId > 0 && versionId > 0 && chunkId > 0);
        }
    }

    public record Corpus(Source source, String text, String sha256) {
        public Corpus {
            Checks.require(source != null);
            Checks.text(text, StrictJson.LINE_LIMIT);
            Checks.sha(sha256);
            Checks.require(StrictJson.textSha(text).equals(sha256));
        }
    }

    public record Binding(String runId, String caseId, String attemptId, String datasetSha256,
                          long userId, long authEpoch, long conversationId, String turnId, String questionSha256) {
        public Binding {
            Checks.id(runId);
            Checks.id(caseId);
            Checks.uuid(attemptId);
            Checks.sha(datasetSha256);
            Checks.require(userId > 0 && authEpoch >= 0 && conversationId > 0);
            Checks.uuid(turnId);
            Checks.sha(questionSha256);
        }
    }

    public record Registration(int schemaVersion, String runId, String caseId, String attemptId,
                               String datasetFile, String datasetSha256, String targetId, String instanceId,
                               long expectedUserId, long conversationId, long expectedLastTurnSeq,
                               String questionSha256, long expiresAtEpochMillis, String artifactKey) {
        public Registration {
            Checks.require(schemaVersion == 1 && expectedUserId > 0 && conversationId > 0
                    && expectedLastTurnSeq == 0 && expiresAtEpochMillis > 0);
            Checks.id(runId);
            Checks.id(caseId);
            Checks.uuid(attemptId);
            Checks.text(datasetFile, 255);
            Checks.sha(datasetSha256);
            Checks.id(targetId);
            Checks.id(instanceId);
            Checks.sha(questionSha256);
            Checks.uuid(artifactKey);
        }
    }

    public record Message(String role, String content) {
        public Message {
            Checks.require(List.of("system", "user", "assistant").contains(role));
            Checks.text(content, StrictJson.FILE_LIMIT);
        }
    }

    public record Material(Source source, String text, boolean contextTruncated, String sha256) {
        public Material {
            Checks.require(source != null);
            Checks.text(text, StrictJson.FILE_LIMIT);
            Checks.sha(sha256);
            Checks.require(StrictJson.textSha(text).equals(sha256));
        }
    }

    public record Usage(Long promptTokens, Long completionTokens) {
        public Usage {
            Checks.require((promptTokens == null && completionTokens == null)
                    || (promptTokens != null && completionTokens != null && promptTokens >= 0 && completionTokens >= 0));
        }
    }

    public record InputSnapshot(int schemaVersion, Binding binding, String instanceId, String model,
                                String promptFormat, List<Message> messages, List<Material> materials,
                                String requestSha256, ModelStage modelStage, String answerSha256,
                                long answerCodePoints, Usage usage, AnswerKind answerKind,
                                BusinessStage businessStage, SettlementStage settlementStage,
                                List<Source> sealedSources) {
        public InputSnapshot {
            Checks.require(schemaVersion == 1 && binding != null && modelStage != null && usage != null
                    && answerKind != null && businessStage != null && settlementStage != null && answerCodePoints >= 0);
            Checks.id(instanceId);
            messages = List.copyOf(messages);
            materials = List.copyOf(materials);
            sealedSources = List.copyOf(sealedSources);
            if (requestSha256 != null) {
                Checks.sha(requestSha256);
            }
            if (answerSha256 != null) {
                Checks.sha(answerSha256);
            }
        }
    }

    public record Attempt(String requestId, String attemptId, Long conversationId, String turnId, String createRequestId) {
        public Attempt(String requestId, String attemptId, Long conversationId, String turnId) {
            this(requestId, attemptId, conversationId, turnId, null);
        }

        public Attempt {
            Checks.uuid(requestId);
            Checks.uuid(attemptId);
            Checks.require(conversationId == null || conversationId > 0);
            if (turnId != null) {
                Checks.uuid(turnId);
            }
            if (createRequestId != null) {
                Checks.uuid(createRequestId);
            }
        }
    }

    public record Answer(String caseId, ExecutionStatus status, Attempt attempt, Binding binding, AnswerKind answerKind,
                         String answer, String persistedAnswerSha256, List<Source> sources,
                         BusinessStage businessStage, SettlementStage settlementStage, Usage usage,
                         EvaluationFailure.Code error) {
        public Answer {
            Checks.id(caseId);
            Checks.require(status != null);
            sources = List.copyOf(sources);
            if (answer != null) {
                Checks.text(answer, StrictJson.FILE_LIMIT);
            }
            if (persistedAnswerSha256 != null) {
                Checks.sha(persistedAnswerSha256);
            }
        }
    }

    public record Collection(int schemaVersion, String runId, String datasetSha256, String toolVersion,
                             String artifactId, String configurationSha256, String model,
                             String corpusSnapshotId, ClaimState corpusState, String aclSnapshotId,
                             ClaimState aclState, SourceType sourceType, List<String> caseIds,
                             Map<String, Long> identityBindings, FileRef answers, Map<String, FileRef> inputs,
                             boolean complete, InvocationOrigin invocationOrigin) {
        public Collection {
            invocationOrigin = invocationOrigin == null ? InvocationOrigin.UNKNOWN : invocationOrigin;
            Checks.require(schemaVersion == 1 && sourceType != null && corpusState != null && aclState != null && answers != null);
            Checks.id(runId);
            Checks.sha(datasetSha256);
            Checks.id(toolVersion);
            Checks.id(artifactId);
            Checks.sha(configurationSha256);
            Checks.text(model, 255);
            Checks.id(corpusSnapshotId);
            Checks.id(aclSnapshotId);
            caseIds = identifiers(caseIds, true);
            identityBindings = Map.copyOf(identityBindings);
            identityBindings.forEach((alias, user) -> {
                Checks.id(alias);
                Checks.require(user > 0);
            });
            inputs = Map.copyOf(inputs);
            inputs.keySet().forEach(Checks::id);
        }
    }

    private static List<String> identifiers(List<String> values, boolean nonempty) {
        Checks.unique(values);
        Checks.require(!nonempty || !values.isEmpty());
        values.forEach(Checks::id);
        return List.copyOf(values);
    }

    private static List<String> texts(List<String> values) {
        Checks.require(values != null);
        values.forEach(value -> Checks.text(value, 16_000));
        return List.copyOf(values);
    }
}
