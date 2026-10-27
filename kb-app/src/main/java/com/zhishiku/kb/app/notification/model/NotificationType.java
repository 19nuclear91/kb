package com.zhishiku.kb.app.notification.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 固定通知类型；提示文本由服务端固定，不来自源事件或客户端。 */
public enum NotificationType {
    REVIEW_SUBMITTED("有版本等待你审核"),
    REVIEW_APPROVED("你送审的版本已通过"),
    REVIEW_REJECTED("你送审的版本被驳回"),
    COMMENT_REPLIED("你的评论有新回复");

    private static final Map<String, NotificationType> BY_SOURCE = Map.of(
            "REVIEW_SUBMITTED", REVIEW_SUBMITTED,
            "REVIEW_APPROVED", REVIEW_APPROVED,
            "REVIEW_REJECTED", REVIEW_REJECTED,
            "COMMENT_REPLIED", COMMENT_REPLIED);
    /** 撤回、取消和发布已由其他提醒或实时工作台覆盖；根评论创建与评论删除不产生个人提醒。 */
    private static final Set<String> IGNORED = Set.of("REVIEW_WITHDRAWN", "REVIEW_CANCELLED", "VERSION_PUBLISHED",
            "COMMENT_CREATED", "COMMENT_DELETED");

    private final String prompt;

    NotificationType(String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
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
