package com.zhishiku.kb.common.review;

/** 操作域与请求标识组成唯一绑定键，不与旧通用管理幂等键共用。 */
public enum ReviewCommandDomain {
    POLICY, SUBMIT, APPROVE, REJECT, WITHDRAW, INDEX_REFRESH
}
