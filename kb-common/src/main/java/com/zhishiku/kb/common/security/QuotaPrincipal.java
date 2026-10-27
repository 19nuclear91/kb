package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * AI 配额使用的可信主体。
 *
 * <p>部门 ID 必须由 kb-app 根据已验证的 Sa-Token 会话解析，不能由客户端请求体或自定义请求头提供。
 *
 * @param userId       当前登录用户内部 ID
 * @param departmentId 当前用户主部门内部 ID
 */
@Schema(name = "QuotaPrincipal", description = "当前会话解析出的 AI 配额主体")
public record QuotaPrincipal(
        @Schema(description = "当前登录用户内部 ID", example = "1001")
        long userId,
        @Schema(description = "由服务端会话解析出的用户主部门内部 ID", example = "10")
        long departmentId) {

    public QuotaPrincipal {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须为正数");
        }
        if (departmentId <= 0) {
            throw new IllegalArgumentException("departmentId 必须为正数");
        }
    }
}
