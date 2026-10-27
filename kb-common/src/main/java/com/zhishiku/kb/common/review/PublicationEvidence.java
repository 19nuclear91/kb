package com.zhishiku.kb.common.review;

/** 发布证据独立于索引状态；旧兼容性由协议来源单独表达。 */
public enum PublicationEvidence {
    NONE,
    PUBLISHED,
    OBSERVED_CURRENT;

    public boolean isPublished() {
        return this != NONE;
    }
}
