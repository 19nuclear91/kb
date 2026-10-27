package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 空间或文档的授权条目。
 *
 * <p>{@code principalKey} 形如 {@code "3:2"}，与 DDL 生成列一致。
 *
 * @param id            授权记录内部 ID
 * @param spaceId       所属空间内部 ID；文档例外授权时表示文档当前所属空间
 * @param principalType 授权主体类型
 * @param principalId   授权主体内部 ID
 * @param principalKey  主体类型和 ID 的稳定组合键
 * @param principalName 主体显示名
 * @param permission    权限位，例如 {@code view}、{@code download}、{@code edit} 或 {@code manage}
 */
@Schema(name = "SpaceAclSummary", description = "空间或文档授权条目")
public record SpaceAclSummary(
        @Schema(description = "授权记录内部 ID", example = "100")
        long id,
        @Schema(description = "所属空间内部 ID", example = "1")
        long spaceId,
        @Schema(description = "授权主体类型", example = "USER")
        PrincipalType principalType,
        @Schema(description = "授权主体内部 ID", example = "2")
        long principalId,
        @Schema(description = "主体类型和 ID 的稳定组合键", example = "1:2")
        String principalKey,
        @Schema(description = "主体显示名", example = "Alice")
        String principalName,
        @Schema(description = "权限位", example = "view")
        String permission) {
}
