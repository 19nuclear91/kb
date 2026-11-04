package com.zhishiku.kb.app.space.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.app.space.model.SpaceAclBatchRevokeItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "同一空间按显式条目批量撤销；请求标识仅通过 X-Request-Id 携带")
public record SpaceAclBatchRevokeRequest(
        @Schema(description = "1 至 300 条授权，主体类型、主体 ID 与权限位的组合不得重复")
        @JsonDeserialize(contentUsing = SpaceAclBatchRevokeItemDeserializer.class)
        List<SpaceAclBatchRevokeItem> items) {
}
