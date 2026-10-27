package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 重试恢复首次成功事件的状态与时间。
 *
 * @param documentId              文档 ID
 * @param previousSecurityLevel   变更前的文档密级
 * @param securityLevel           文档密级
 * @param previousSecurityVersion 变更前的密级版本
 * @param securityVersion         文档密级的独立乐观锁版本
 * @param requestId               请求幂等标识
 * @param changedAt               首次成功变更的时间
 * @param replayed                是否重放此前成功操作的回执
 */
@Schema(description = "文档密级调整的首次成功结果或幂等重放回执。")
public record DocumentSecurityLevelResult(
        @Schema(description = "文档 ID") long documentId,
        @Schema(description = "变更前的文档密级") short previousSecurityLevel,
        @Schema(description = "文档密级") short securityLevel,
        @Schema(description = "变更前的密级版本") long previousSecurityVersion,
        @Schema(description = "文档密级的独立乐观锁版本") long securityVersion,
        @Schema(description = "请求幂等标识") String requestId,
        @Schema(description = "首次成功变更的时间") Instant changedAt,
        @Schema(description = "是否重放此前成功操作的回执") boolean replayed) {
}
