package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import java.time.OffsetDateTime;
import java.util.UUID;

public record SuggestionJob(UUID requestId, UUID executionId, long actorId, long authEpoch, long departmentId,
                            long docId, long spaceId, long sourceVersionId, long metadataVersion, long treeVersion,
                            OffsetDateTime deadline, String generationStatus, String reasonCode, OffsetDateTime dispatchedAt,
                            Long reservedTokens, String usageStatus, Long promptTokens, Long completionTokens,
                            Long plannedCharge, Long settledCharge, String settlementStatus) {

    public boolean running() {
        return "RUNNING".equals(generationStatus);
    }

    public String conflict(SuggestionAccess.Context current) {
        if (current.versionId() == null || sourceVersionId != current.versionId()) {
            return "SOURCE_CHANGED";
        }
        if (spaceId != current.spaceId() || metadataVersion != current.metadataVersion()) {
            return "METADATA_VERSION_CONFLICT";
        }
        if (treeVersion != current.treeVersion()) {
            return "TREE_VERSION_CONFLICT";
        }
        return null;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(UUID requestId, long sourceVersionId, long metadataVersion, String status, String reasonCode,
                       String usageStatus, Long promptTokens, Long completionTokens, Long reservedTokens,
                       Long plannedCharge, Long settledCharge, String settlementStatus,
                       OffsetDateTime expiresAt, SuggestionData.Result result) {
    }

    public View view(String reason, OffsetDateTime expiry, SuggestionData.Result result) {
        return new View(requestId, sourceVersionId, metadataVersion, generationStatus, reason, usageStatus,
                promptTokens, completionTokens, reservedTokens, plannedCharge, settledCharge, settlementStatus, expiry, result);
    }
}
