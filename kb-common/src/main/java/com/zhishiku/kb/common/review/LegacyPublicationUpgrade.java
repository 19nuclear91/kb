package com.zhishiku.kb.common.review;

/** 旧行升级的离线映射规则；不生成事件、不推测历史发布时间或上传者。 */
public final class LegacyPublicationUpgrade {
    private LegacyPublicationUpgrade() {
    }

    public static VersionPublication map(long documentId, long versionDocumentId, long versionId,
                                          Long currentVersionId, String documentStatus) {
        if (documentId <= 0 || versionId <= 0 || versionDocumentId != documentId) {
            throw new IllegalArgumentException("Version/document ownership mismatch");
        }
        boolean observed = "published".equals(documentStatus)
                && currentVersionId != null && currentVersionId == versionId;
        return new VersionPublication(PublicationPolicy.AUTO, PublicationProtocol.LEGACY_COMPAT, null,
                observed ? PublicationEvidence.OBSERVED_CURRENT : PublicationEvidence.NONE, null);
    }
}
