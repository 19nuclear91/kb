package com.zhishiku.kb.app.notification.model;

import java.util.UUID;

/** SQL 授权与工作身份的最小事实；全空用于旧来源嵌套映射，不含问题正文或资料身份。 */
public record GapNotificationFact(Long gapId, UUID eventId, String eventKind, String state, Long version, Long reporterUserId,
                                  Long assigneeUserId, Long assignmentEpoch, Long eventAssignmentEpoch, UUID queueEventId,
                                  UUID pendingSolutionId, UUID eventSolutionId, Long eventResultVersion, Boolean visible, Boolean workCurrent) {
    public boolean currentWork(long recipient) {
        if (gapId == null || gapId <= 0 || eventId == null || eventKind == null || state == null || version == null || version < 0
                || reporterUserId == null || reporterUserId <= 0 || assignmentEpoch == null || assignmentEpoch < 0
                || eventAssignmentEpoch == null || eventAssignmentEpoch < 0 || eventResultVersion == null || eventResultVersion < 0
                || (assigneeUserId != null && assigneeUserId <= 0)) {
            throw new IllegalArgumentException("Inconsistent gap notification fact");
        }
        return switch (eventKind) {
            case "GAP_CREATED", "GAP_RETURNED" -> state.equals("SUBMITTED") && eventId.equals(queueEventId);
            case "GAP_ASSIGNED", "GAP_SOLUTION_REJECTED" -> state.equals("IN_PROGRESS")
                    && Long.valueOf(recipient).equals(assigneeUserId) && assignmentEpoch.equals(eventAssignmentEpoch);
            case "GAP_SOLUTION_SUBMITTED" -> state.equals("AWAITING_CONFIRMATION") && reporterUserId == recipient
                    && eventSolutionId != null && eventSolutionId.equals(pendingSolutionId);
            case "GAP_RESOLVED" -> state.equals("RESOLVED") && version.equals(eventResultVersion);
            case "GAP_WITHDRAWN" -> state.equals("WITHDRAWN") && version.equals(eventResultVersion);
            case "GAP_CLOSED" -> state.equals("CLOSED") && version.equals(eventResultVersion);
            default -> false;
        };
    }
}
