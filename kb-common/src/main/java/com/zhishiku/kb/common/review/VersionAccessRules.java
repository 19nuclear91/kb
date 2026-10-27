package com.zhishiku.kb.common.review;

import java.util.Objects;

/** 当前 view 已含有效身份、归属、归档及密级；参与者身份不能取代该前提。 */
public final class VersionAccessRules {
    private VersionAccessRules() {
    }

    public static boolean allows(VersionPublication version, VersionAccessPurpose purpose,
                                  boolean currentView, boolean candidateParticipant) {
        Objects.requireNonNull(purpose, "purpose");
        if (!currentView) return false;
        return switch (purpose) {
            case INTERACTIVE -> version.evidence().isPublished() || version.protocol() == PublicationProtocol.LEGACY_COMPAT
                    || candidateParticipant;
            case MODEL_SOURCE -> version.permitsModelSource();
            case COMMENT_TARGET -> version.permitsCommentTarget();
        };
    }
}
