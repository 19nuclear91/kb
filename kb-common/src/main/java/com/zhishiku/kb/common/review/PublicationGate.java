package com.zhishiku.kb.common.review;

import java.util.Objects;

/** app 与 Worker 的业务门禁契约，不包含数据库锁或网络行为。 */
public final class PublicationGate {
    private PublicationGate() {
    }

    public static void require(VersionPublication publication, ReviewState decision, PublicationCheck check) {
        if (!check.active()) throw new ReviewFailure(ReviewError.EMBEDDING_MAINTENANCE);
        if (!check.live() || check.versionNo() < 1 || (check.currentVersionNo() != null && check.versionNo() <= check.currentVersionNo())
                || !publication.permitsPublication(check.indexStatus(), decision))
            throw new ReviewFailure(ReviewError.STATE_CONFLICT);
        if (check.activeProfileId() == null || check.activeFingerprint() == null
                || !Objects.equals(check.activeProfileId(), check.indexedProfileId())
                || !Objects.equals(check.activeFingerprint(), check.indexedFingerprint())
                || check.indexedEpoch() == null || check.indexedEpoch() < 0 || check.indexedEpoch() > check.controlEpoch()
                || !check.chunksIntact()) throw new ReviewFailure(ReviewError.INDEX_REFRESH_REQUIRED);
    }
}
