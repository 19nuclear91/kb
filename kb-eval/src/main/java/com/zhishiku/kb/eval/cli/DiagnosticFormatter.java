package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.input.EvaluationException;

import java.util.Set;

/**
 * 即使调用者错误地传入外部文本，定位也只允许固定角色与字段。
 */
public final class DiagnosticFormatter {
    private static final Set<String> FIELDS = Set.of("arguments", "schemaVersion", "datasetId",
            "datasetVersion", "corpusSnapshotId", "labelsFile", "runId", "configurationId",
            "sourceType", "labelsSha256", "resultsFile", "caseId", "tags", "expectedEmpty",
            "relevance", "docId", "versionId", "grade", "status", "hits", "chunkId", "latencyMs",
            "baseline", "rules", "metric", "k", "min", "max", "maxRegression", "output", "json", "file");

    private DiagnosticFormatter() {
    }

    public static String format(EvaluationException error) {
        String role = error.role() != null && error.role().matches(
                "(?:cli|dataset(?:\\.labels)?|thresholds|output|run\\[[1-9][0-9]*](?:\\.results)?)")
                ? error.role() : "input";
        String field = FIELDS.contains(error.field() == null ? "" : error.field()) ? error.field() : "input";
        return "EVAL_ERROR " + error.code().name() + " role=" + role
                + (error.line() > 0 ? " line=" + error.line() : "") + " field=" + field;
    }
}
