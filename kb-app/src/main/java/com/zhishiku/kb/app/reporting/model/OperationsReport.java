package com.zhishiku.kb.app.reporting.model;

import java.util.List;
import java.util.Map;

/** 固定安全投影：没有资源身份、名称、正文或事件载荷。 */
public record OperationsReport(String definitionVersion, String asOf, String from, String to,
                               String dayTimeZone, Inventory inventory, Review review,
                               Comments comments, List<Day> days) {
    public record Inventory(String documentCount, String archivedDocumentCount, String versionCount,
                            Map<String, String> formats, Map<String, String> parsingStates) {}

    public record Coverage(String status, String coverageStartedAt, String effectiveFrom, String effectiveTo) {}

    public record Review(String pendingReviewCount, String approvedCount, String rejectedCount,
                         String durationSampleCount, String durationUnknownCount, String decisionElapsedMsTotal,
                         String meanDecisionMs, Coverage coverage) {}

    public record ReviewWindow(String approvedCount, String rejectedCount, String durationSampleCount,
                               String durationUnknownCount, String decisionElapsedMsTotal,
                               String meanDecisionMs, Coverage coverage) {}

    public record Comments(String rootCommentCount, String replyCount, Coverage coverage) {}

    public record Day(String day, String from, String to, boolean partialDay,
                      ReviewWindow review, Comments comments) {}
}
