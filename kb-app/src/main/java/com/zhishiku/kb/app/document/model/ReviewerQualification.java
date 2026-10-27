package com.zhishiku.kb.app.document.model;

import java.util.Objects;

/**
 * 受限审核资格查询的当次事实，不对外输出人员目录或缓存身份。
 *
 * @param userId 候选审核者标识
 * @param enabled 当前主体有效
 * @param departmentEnabled 当前主部门有效
 * @param clearance 当前主体密级
 * @param securityLevel 当前文档密级
 * @param canView 当前文档 view（可来自文档例外）
 * @param canReview 当前实际空间 review
 */
public record ReviewerQualification(long userId, boolean enabled, boolean departmentEnabled,
                                     short clearance, short securityLevel, boolean canView, boolean canReview) {
    public boolean eligible(Long uploader, Long submittedBy) {
        return userId > 0 && enabled && departmentEnabled && canView && canReview && clearance >= securityLevel
                && !Objects.equals(userId, uploader) && !Objects.equals(userId, submittedBy);
    }
}
