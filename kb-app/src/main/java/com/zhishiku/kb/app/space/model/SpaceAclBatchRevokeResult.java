package com.zhishiku.kb.app.space.model;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "空间批量撤销回执；不存在的条目同样视为成功")
public record SpaceAclBatchRevokeResult(
        @Schema(description = "目标空间 ID")
        long spaceId,
        @Schema(description = "本批显式提交条目的逐条撤销结果")
        List<Item> items,
        @Schema(description = "本批实际移除的授权条目数")
        long removedCount,
        @Schema(description = "本批原本不存在的授权条目数")
        long absentCount,
        @Schema(description = "是否从首次成功的审计快照重放")
        boolean replayed) {

    @Schema(description = "批量撤销的单条结果")
    public record Item(
            @Schema(description = "主体类型：USER、ROLE、DEPT")
            PrincipalType principalType,
            @Schema(description = "主体 ID")
            long principalId,
            @Schema(description = "权限位：view、download、edit、review、comment、manage")
            String permission,
            @Schema(description = "是否实际移除；false 表示原本不存在，并非失败")
            boolean removed) {
    }
}
