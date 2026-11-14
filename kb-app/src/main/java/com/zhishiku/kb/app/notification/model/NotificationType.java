package com.zhishiku.kb.app.notification.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 固定通知类型；提示文本由服务端固定，不来自源事件或客户端。 */
public enum NotificationType {
    REVIEW_SUBMITTED("有版本等待你审核"),
    REVIEW_APPROVED("你送审的版本已通过"),
    REVIEW_REJECTED("你送审的版本被驳回"),
    COMMENT_REPLIED("你的评论有新回复"),
    GAP_CREATED("有新的知识问题等待受理"),
    GAP_RETURNED("有知识问题退回受理"),
    GAP_ASSIGNED("有知识问题分派给你"),
    GAP_SOLUTION_SUBMITTED("你的知识问题有方案待确认"),
    GAP_SOLUTION_REJECTED("知识问题方案仍未解决"),
    GAP_RESOLVED("知识问题已由提问者确认解决"),
    GAP_WITHDRAWN("知识问题已被提问者撤回"),
    GAP_CLOSED("知识问题已带原因关闭");

    private static final Map<String, NotificationType> BY_SOURCE = Map.ofEntries(
            Map.entry("REVIEW_SUBMITTED", REVIEW_SUBMITTED), Map.entry("REVIEW_APPROVED", REVIEW_APPROVED),
            Map.entry("REVIEW_REJECTED", REVIEW_REJECTED), Map.entry("COMMENT_REPLIED", COMMENT_REPLIED),
            Map.entry("GAP_CREATED", GAP_CREATED), Map.entry("GAP_RETURNED", GAP_RETURNED), Map.entry("GAP_ASSIGNED", GAP_ASSIGNED),
            Map.entry("GAP_SOLUTION_SUBMITTED", GAP_SOLUTION_SUBMITTED), Map.entry("GAP_SOLUTION_REJECTED", GAP_SOLUTION_REJECTED),
            Map.entry("GAP_RESOLVED", GAP_RESOLVED), Map.entry("GAP_WITHDRAWN", GAP_WITHDRAWN), Map.entry("GAP_CLOSED", GAP_CLOSED));
    /** 撤回、取消和发布已由其他提醒或实时工作台覆盖；根评论创建与评论删除不产生个人提醒。 */
    private static final Set<String> IGNORED = Set.of("REVIEW_WITHDRAWN", "REVIEW_CANCELLED", "VERSION_PUBLISHED",
            "COMMENT_CREATED", "COMMENT_DELETED", "NOTE_ADDED");

    private final String prompt;

    NotificationType(String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
    }

    public boolean knowledgeGap() {
        return name().startsWith("GAP_");
    }

    /** 源事件种类是否明确不产生提醒；未知种类不属于此集合，需安全等待。 */
    public static boolean ignored(String sourceKind) {
        return sourceKind != null && IGNORED.contains(sourceKind);
    }

    /** 源事件种类对应的提醒类型；空表示忽略或尚未识别。 */
    public static Optional<NotificationType> fromSource(String sourceKind) {
        return sourceKind == null ? Optional.empty() : Optional.ofNullable(BY_SOURCE.get(sourceKind));
    }
}
