package com.zhishiku.kb.common.conversation;

/**
 * 固定文档版本身份，无当前版本回退。
 *
 * @param documentId 文档 ID
 * @param versionId  版本 ID
 */
public record SourceKey(long documentId, long versionId) implements Comparable<SourceKey> {
    public SourceKey {
        if (documentId <= 0 || versionId <= 0) throw new IllegalArgumentException("来源身份无效");
    }

    @Override
    public int compareTo(SourceKey other) {
        int documents = Long.compare(documentId, other.documentId);
        return documents != 0 ? documents : Long.compare(versionId, other.versionId);
    }
}

