package com.zhishiku.kb.app.space.model;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "空间批量授权主体")
public record SpaceAclPrincipal(
        @Schema(description = "主体类型：USER、ROLE、DEPT；部门授权向下生效")
        PrincipalType principalType,
        @Schema(description = "主体正整数 ID")
        long principalId) {
}
