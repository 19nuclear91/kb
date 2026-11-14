package com.zhishiku.kb.app.knowledgegap.model;

import java.time.Instant;
import java.util.UUID;

/** 本模块持久化投影；包含正文的行不得进入日志或通用审计。 */
public final class KnowledgeGapRows {
    private KnowledgeGapRows() {
    }

    public record Gap(long id, long reporterUserId, KnowledgeGapCategory category, KnowledgeGapSource source,
                      String description, String consentVersion, KnowledgeGapState state, long version,
                      Long assigneeUserId, long assignmentEpoch, UUID pendingSolutionId, UUID queueEventId,
                      Instant createdAt, Instant updatedAt) {
        public KnowledgeGapWorkflow workflow() {
            return new KnowledgeGapWorkflow(state, version, assigneeUserId, assignmentEpoch,
                    pendingSolutionId, queueEventId);
        }

        @Override
        public String toString() {
            return "Gap[redacted]";
        }
    }

    public record Entry(UUID entryId, long gapId, long position, String kind, long authorUserId,
                        String body, Long documentId, Long versionId, Instant createdAt) {
        @Override
        public String toString() {
            return "Entry[redacted]";
        }
    }

    public record Event(UUID eventId, long gapId, String kind, long resultVersion, long actorUserId,
                        Long assigneeUserId, long assignmentEpoch, UUID entryId, UUID solutionId,
                        Instant occurredAt) {
    }

    /** 绑定输入只保存摘要；结果只保存必要身份与状态，不复制正文和文档身份。 */
    public record Command(String requestId, long actorUserId, KnowledgeGapAction operation, Long gapId,
                          Long expectedVersion, String inputDigest, Long resultGapId, Long resultVersion,
                          KnowledgeGapState resultState, UUID resultEventId, UUID resultSolutionId) {
    }

    public record Binding(String requestId, long actorUserId, KnowledgeGapAction operation, Long gapId,
                          Long expectedVersion, String inputDigest) {
    }

    public record Receipt(long gapId, long resultVersion, KnowledgeGapState state, UUID eventId,
                          UUID solutionId, boolean replayed) {
    }
}
