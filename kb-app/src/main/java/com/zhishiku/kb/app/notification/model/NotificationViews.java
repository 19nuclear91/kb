package com.zhishiku.kb.app.notification.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 本人通知 API 的最小固定投影；不含原始事件载荷、标题快照、正文或用户目录。 */
public final class NotificationViews {
    private NotificationViews() {
    }

    /**
     * 当前可见的通知项。
     *
     * @param notificationId 通知 UUID
     * @param type 固定通知类型
     * @param prompt 服务端固定中文提示
     * @param createdAt 通知实际生成时刻
     * @param readAt 首次已读时间；未读为空
     * @param target 当前可见时的固定站内目标
     */
    @Schema(description = "本人当前可见通知")
    public record Item(
            @Schema(description = "通知 UUID") UUID notificationId,
            @Schema(description = "固定通知类型") NotificationType type,
            @Schema(description = "服务端固定提示，不含源正文") String prompt,
            @Schema(description = "通知生成时间") Instant createdAt,
            @Schema(description = "首次已读时间；未读为空") Instant readAt,
            @Schema(description = "固定站内目标") NotificationTarget target) {
    }

    /**
     * 先过滤后分页的列表页。
     *
     * @param items 当前页可见通知
     * @param hasMore 是否还有可见项
     * @param nextCursor 续页游标；末页为空
     * @param asOf 本次读取的服务器时间，不承诺与计数冻结一致
     */
    @Schema(description = "本人通知分页结果，不提供原始总数")
    public record Page(
            @Schema(description = "当前页通知") List<Item> items,
            @Schema(description = "是否还有更多可见通知") boolean hasMore,
            @Schema(description = "续页游标；末页为空") String nextCursor,
            @Schema(description = "本次读取时间") Instant asOf) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * 当前可见未读数。
     *
     * @param count 精确十进制字符串
     * @param asOf 本次计数的服务器时间
     */
    @Schema(description = "本人当前可见未读数；不等于审核待办数")
    public record UnreadCount(
            @Schema(description = "精确十进制字符串") String count,
            @Schema(description = "本次计数时间") Instant asOf) {
    }

    /**
     * 已读回执。
     *
     * @param notificationId 通知 UUID
     * @param readAt 首次已读时间，重复操作保持不变
     */
    @Schema(description = "本人通知已读回执，不含目标")
    public record ReadReceipt(
            @Schema(description = "通知 UUID") UUID notificationId,
            @Schema(description = "首次已读时间") Instant readAt) {
    }
}
