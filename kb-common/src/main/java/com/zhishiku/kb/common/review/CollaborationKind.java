package com.zhishiku.kb.common.review;

/** 无正文的业务事实种类；批准与发布是两个独立事实，评论事实只记录结构身份。 */
public enum CollaborationKind {
    REVIEW_SUBMITTED, REVIEW_APPROVED, REVIEW_REJECTED, REVIEW_WITHDRAWN, REVIEW_CANCELLED, VERSION_PUBLISHED,
    COMMENT_CREATED, COMMENT_REPLIED, COMMENT_DELETED;

    /** 评论事实按评论身份去重，不携带审核轮次。 */
    public boolean comment() {
        return this == COMMENT_CREATED || this == COMMENT_REPLIED || this == COMMENT_DELETED;
    }
}
