package com.zhishiku.kb.common.review;

/** 候选人工核对、模型来源和后续评论目标不能共用放行语义。 */
public enum VersionAccessPurpose {
    INTERACTIVE, MODEL_SOURCE, COMMENT_TARGET
}
