package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 初评与人工修订共用的语义值，位置仍由确定性规则独立验证。 */
public final class JudgmentModel {
    private JudgmentModel() {
    }

    public enum FactVerdict { COVERED, MISSING, CONTRADICTED, UNCERTAIN }
    public enum ClaimVerdict { SUPPORTED, UNSUPPORTED, CONTRADICTED, UNCERTAIN }
    public enum Relevance { RELEVANT, IRRELEVANT, UNCERTAIN }
    public enum Behavior { ANSWER, ABSTAIN, UNCERTAIN }
    public enum Status { COMPLETE, EXECUTION_INCOMPLETE, JUDGE_FAILED, NOT_EVALUATED }

    public record Span(@JsonProperty(required = true) int start, int end, String quote) {
        public Span {
            Checks.require(start >= 0 && end > start);
            Checks.text(quote, 16_000);
        }
    }

    public record Evidence(DataModel.Source source, Span range) {
        public Evidence {
            Checks.require(source != null && range != null);
        }
    }

    public record Fact(String factId, FactVerdict verdict, String reason) {
        public Fact {
            Checks.id(factId);
            Checks.require(verdict != null);
            Checks.text(reason, 8000);
        }
    }

    public record CriticalError(String errorId, Span answer, String reason) {
        public CriticalError {
            Checks.id(errorId);
            Checks.require(answer != null);
            Checks.text(reason, 8000);
        }
    }

    public record Claim(String claimId, Span answer, ClaimVerdict verdict, List<Evidence> evidence, String reason) {
        public Claim {
            Checks.id(claimId);
            Checks.require(answer != null && verdict != null);
            evidence = List.copyOf(evidence);
            Checks.require(verdict != ClaimVerdict.SUPPORTED || !evidence.isEmpty());
            Checks.text(reason, 8000);
        }
    }

    public record Material(DataModel.Source source, Relevance verdict, String reason) {
        public Material {
            Checks.require(source != null && verdict != null);
            Checks.text(reason, 8000);
        }
    }

    public record Correctness(List<Fact> facts, Behavior behavior, List<CriticalError> criticalErrors) {
        public Correctness {
            facts = List.copyOf(facts);
            criticalErrors = List.copyOf(criticalErrors);
            Checks.require(behavior != null);
            Checks.unique(facts.stream().map(Fact::factId).toList());
            Checks.unique(criticalErrors.stream().map(CriticalError::errorId).toList());
        }
    }

    public record Grounding(List<Claim> claims, List<Material> materials, @JsonProperty(required = true) boolean noVerifiableClaims) {
        public Grounding {
            claims = List.copyOf(claims);
            materials = List.copyOf(materials);
            Checks.unique(claims.stream().map(Claim::claimId).toList());
            Checks.require(noVerifiableClaims == claims.isEmpty());
        }
    }

    public record Binding(String datasetSha256, String collectionSha256, String runId, String caseId,
                          String answerSha256, String inputSha256, String rubricVersion, String rubricSha256,
                          String judgeConfigurationSha256) {
        public Binding {
            Checks.sha(datasetSha256);
            Checks.sha(collectionSha256);
            Checks.id(runId);
            Checks.id(caseId);
            if (answerSha256 != null) {
                Checks.sha(answerSha256);
            }
            if (inputSha256 != null) {
                Checks.sha(inputSha256);
            }
            Checks.id(rubricVersion);
            Checks.sha(rubricSha256);
            Checks.sha(judgeConfigurationSha256);
        }
    }

    public record Call(String view, String requestSha256, String stage, DataModel.Usage usage) {
        public Call {
            Checks.require(List.of("correctness", "grounding").contains(view) && usage != null
                    && List.of("PREPARED", "SUBMITTED", "COMPLETE", "FAILED").contains(stage));
            Checks.sha(requestSha256);
        }
    }

    public record Judgment(int schemaVersion, String judgeRunId, Binding binding, Status status,
                           Correctness correctness, Grounding grounding, List<Call> calls, EvaluationFailure.Code error) {
        public Judgment {
            Checks.require(schemaVersion == 1 && binding != null && status != null);
            Checks.uuid(judgeRunId);
            calls = List.copyOf(calls);
            Checks.require(calls.size() <= 2);
            Checks.require(status != Status.COMPLETE || correctness != null && grounding != null && error == null);
        }
    }

    public record Manifest(int schemaVersion, String judgeRunId, String datasetSha256, String collectionSha256,
                           String configurationSha256, String model, String rubricVersion, String rubricSha256,
                           List<String> caseIds, DataModel.FileRef judgments, int submittedCalls,
                           long estimatedInputTokens, boolean complete, DataModel.InvocationOrigin invocationOrigin) {
        public Manifest {
            invocationOrigin = invocationOrigin == null ? DataModel.InvocationOrigin.UNKNOWN : invocationOrigin;
            Checks.require(schemaVersion == 1 && judgments != null && submittedCalls >= 0 && estimatedInputTokens >= 0);
            Checks.uuid(judgeRunId);
            Checks.sha(datasetSha256);
            Checks.sha(collectionSha256);
            Checks.sha(configurationSha256);
            Checks.sha(rubricSha256);
            Checks.id(rubricVersion);
            Checks.text(model, 255);
            Checks.unique(caseIds);
            caseIds = List.copyOf(caseIds);
        }
    }
}
