package com.zhishiku.kb.app.auth.model;

/** 处理人池的窄契约；资格明细只供服务内检查，不作为候选接口响应。 */
public final class KnowledgeGapAssigneeViews {
    private KnowledgeGapAssigneeViews() {
    }

    public record Candidate(long userId, String label) {
    }

    public record Qualification(long userId, boolean enabled, boolean departmentEnabled,
                                boolean passwordChangeRequired, boolean handlePermission) {
        public boolean eligible() {
            return userId > 0 && enabled && departmentEnabled && !passwordChangeRequired && handlePermission;
        }
    }
}
