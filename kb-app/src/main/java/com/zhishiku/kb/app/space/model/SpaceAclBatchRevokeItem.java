package com.zhishiku.kb.app.space.model;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "空间批量撤销的显式授权条目")
public record SpaceAclBatchRevokeItem(
        @Schema(description = "主体类型：USER、ROLE、DEPT")
        PrincipalType principalType,
        @Schema(description = "主体正整数 ID；允许撤销已删除角色的残留授权")
        long principalId,
        @Schema(description = "权限位：view、download、edit、review、comment、manage")
        String permission) {
}
