package com.zhishiku.kb.app.space.web.dto;

import com.zhishiku.kb.app.space.model.SpaceAclPrincipal;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "同一空间多主体多权限位授予请求；请求标识仅通过 X-Request-Id 携带")
public record SpaceAclBatchGrantRequest(
        @Schema(description = "1 至 50 个不重复的授权主体")
        List<SpaceAclPrincipal> principals,
        @Schema(description = "1 至 6 个不重复的权限位：view、download、edit、review、comment、manage")
        List<String> permissions) {
}
