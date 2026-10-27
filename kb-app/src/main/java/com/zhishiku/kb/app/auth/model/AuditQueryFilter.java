package com.zhishiku.kb.app.auth.model;

import java.time.Instant;

/**
 * 已规范化的审计只读条件；不承载授权事实。
 *
 * @param actorUserId    操作者过滤
 * @param operationScope 操作域精确值
 * @param operation      操作精确值
 * @param targetType     目标类型精确值
 * @param targetId       目标编号
 * @param status         结果过滤
 * @param requestId      请求标识精确值
 * @param loginUsername  登录尝试用户名精确值
 * @param from           含起点
 * @param to             不含终点
 * @param beforeTime     倒序游标时间
 * @param beforeId       倒序游标编号
 * @param fetch          有界读取数量
 */
public record AuditQueryFilter(Long actorUserId, String operationScope, String operation,
                               String targetType, Long targetId, String status, String requestId, String loginUsername,
                               Instant from, Instant to, Instant beforeTime, Long beforeId, int fetch) {
}
