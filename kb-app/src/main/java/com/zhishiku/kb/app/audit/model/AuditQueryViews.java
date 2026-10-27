package com.zhishiku.kb.app.audit.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 审计输出白名单；不暴露持久化对象或原摘要。
 */
public final class AuditQueryViews {
    private AuditQueryViews() {
    }

    /**
     * 事件公共元数据。
     *
     * @param id                内部排序编号
     * @param eventId           事件唯一编号
     * @param operationScope    操作域
     * @param requestId         请求标识
     * @param actorUserId       可空操作者
     * @param targetType        目标类型
     * @param targetId          可空目标编号
     * @param operation         操作
     * @param status            结果
     * @param createdAt         记录时间
     * @param attemptedUsername 登录事件的有界尝试用户名
     */
    @Schema(description = "安全事件元数据")
    public record Metadata(@Schema(description = "内部编号") long id,
                           @Schema(description = "事件编号") UUID eventId,
                           @Schema(description = "操作域") String operationScope,
                           @Schema(description = "请求标识") String requestId,
                           @Schema(description = "操作者编号，可空") Long actorUserId,
                           @Schema(description = "目标类型，保留实际大小写") String targetType,
                           @Schema(description = "目标编号，可空") Long targetId,
                           @Schema(description = "操作") String operation,
                           @Schema(description = "结果") String status,
                           @Schema(description = "时间") Instant createdAt,
                           @Schema(description = "仅登录事件提供的有界尝试用户名") String attemptedUsername) {
    }

    /**
     * 已筛选的标量变更。
     *
     * @param field  已注册的字段名
     * @param before 旧值，仅布尔、整数或已知枚举
     * @param after  新值，仅布尔、整数或已知枚举
     */
    @Schema(description = "白名单标量变更")
    public record Change(@Schema(description = "字段") String field,
                         @Schema(description = "旧值") Object before,
                         @Schema(description = "新值") Object after) {
    }

    /**
     * 详情只包含白名单摘要。
     *
     * @param metadata         安全元数据
     * @param changes          安全变更列表
     * @param summaryAvailable 是否成功应用已注册摘要规则
     */
    @Schema(description = "安全审计详情")
    public record Detail(@Schema(description = "元数据") Metadata metadata,
                         @Schema(description = "结构化变更") List<Change> changes,
                         @Schema(description = "摘要是否可用") boolean summaryAvailable) {
    }

    /**
     * 审计页同时返回实际生效窗口。
     *
     * @param items      事件列表
     * @param hasMore    是否存在后续页
     * @param nextCursor 后续游标
     * @param from       含起点，UTC 微秒精度
     * @param to         不含终点，UTC 微秒精度
     */
    @Schema(description = "审计查询页与固定窗口")
    public record Page(@Schema(description = "事件列表") List<Metadata> items,
                       @Schema(description = "是否存在后续页") boolean hasMore,
                       @Schema(description = "后续游标") String nextCursor,
                       @Schema(description = "实际含起点") Instant from,
                       @Schema(description = "实际不含终点") Instant to) {
    }
}
