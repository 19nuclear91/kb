package com.zhishiku.kb.app.space.model;

import com.zhishiku.kb.common.security.PrincipalType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "空间批量授予回执；授权不修改主体密级")
public record SpaceAclBatchGrantResult(
        @Schema(description = "目标空间 ID")
        long spaceId,
        @Schema(description = "主体与权限位笛卡尔积的逐条结果")
        List<Item> items,
        @Schema(description = "本批新写入的授权条目数")
        long grantedCount,
        @Schema(description = "本批已存在的授权条目数")
        long alreadyGrantedCount,
        @Schema(description = "至少有一条新授权且因密级当前不生效的主体数，按主体计数")
        long clearanceBlockedPrincipalCount,
        @Schema(description = "是否从首次成功的审计快照重放")
        boolean replayed) {

    @Schema(description = "批量授予的单条结果")
    public record Item(
            @Schema(description = "主体类型：USER、ROLE、DEPT")
            PrincipalType principalType,
            @Schema(description = "主体 ID")
            long principalId,
            @Schema(description = "权限位：view、download、edit、review、comment、manage")
            String permission,
            @Schema(description = "该条授权是否已存在；为 true 时未重复写入")
            boolean alreadyGranted) {
    }
}
