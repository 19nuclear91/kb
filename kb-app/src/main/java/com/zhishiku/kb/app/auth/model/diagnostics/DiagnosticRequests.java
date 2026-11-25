package com.zhishiku.kb.app.auth.model.diagnostics;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 公开请求形状；实际解码在有界流上严格拒绝重复或额外字段。 */
public final class DiagnosticRequests {
    private DiagnosticRequests() {
    }

    @Schema(description = "资源上下文用户显示名搜索，名称只经正文传输")
    public record Search(
            @Schema(description = "显示名的字面搜索词，最多 128 个 Unicode code point；缺省为空")
            String q,
            @Schema(description = "每页条数，1–100，缺省 20", minimum = "1", maximum = "100", defaultValue = "20")
            Integer limit,
            @Schema(description = "上一页游标，绑定操作者、认证代次、资源和搜索条件；首页省略")
            String cursor
    ) {
    }

    @Schema(description = "解释一个用户；文档路径不允许 documentId 字段")
    public record Explain(
            @Schema(description = "被分析用户 ID，精确 JSON 正整数", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long userId,
            @Schema(description = "仅空间路径可附选同空间一份文档，精确 JSON 正整数；未选时省略", minimum = "1")
            Long documentId
    ) {
    }

    @Schema(description = "一次只读预演；意图类型必须匹配路径资源类型")
    public record Preview(
            @Schema(description = "唯一被分析用户 ID，精确 JSON 正整数", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long userId,
            @Schema(description = "仅空间路径可附选同空间一份文档；文档路径禁止此字段", minimum = "1")
            Long documentId,
            @Schema(description = "单次现有授权操作的完整意图", requiredMode = Schema.RequiredMode.REQUIRED)
            Intent intent
    ) {
    }

    @Schema(description = "按 type 区分的封闭意图联合", oneOf = {SpaceGrant.class, SpaceRevoke.class, DocumentChange.class}, discriminatorProperty = "type")
    public sealed interface Intent permits SpaceGrant, SpaceRevoke, DocumentChange {
    }

    @Schema(description = "授权主体引用，不代表观察用户身份")
    public record Principal(
            @Schema(description = "USER 用户、ROLE 角色、DEPT 部门", allowableValues = {"USER", "ROLE", "DEPT"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String principalType,
            @Schema(description = "主体 ID，精确 JSON 正整数", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long principalId
    ) {
    }

    @Schema(description = "空间批量授予，主体与权限位取完整笛卡尔积")
    public record SpaceGrant(
            @Schema(description = "固定 SPACE_GRANT", allowableValues = "SPACE_GRANT", requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(description = "1–50 个不同主体，重复项拒绝", requiredMode = Schema.RequiredMode.REQUIRED)
            List<Principal> principals,
            @Schema(description = "1–6 个不同权限位：view、download、edit、review、comment、manage", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> permissions
    ) implements Intent {
    }

    @Schema(description = "一条显式空间撤销项")
    public record RevokeItem(
            @Schema(description = "USER 用户、ROLE 角色、DEPT 部门", allowableValues = {"USER", "ROLE", "DEPT"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String principalType,
            @Schema(description = "主体 ID，精确 JSON 正整数；允许撤销已删除主体的残留行", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long principalId,
            @Schema(description = "view、download、edit、review、comment 或 manage", requiredMode = Schema.RequiredMode.REQUIRED)
            String permission
    ) {
    }

    @Schema(description = "空间显式条目撤销，不取额外笛卡尔积")
    public record SpaceRevoke(
            @Schema(description = "固定 SPACE_REVOKE", allowableValues = "SPACE_REVOKE", requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(description = "1–300 条不同撤销项，重复项拒绝", requiredMode = Schema.RequiredMode.REQUIRED)
            List<RevokeItem> items
    ) implements Intent {
    }

    @Schema(description = "文档单项例外授予或撤销，不接受 review 或 manage")
    public record DocumentChange(
            @Schema(description = "DOCUMENT_GRANT 授予或 DOCUMENT_REVOKE 撤销", allowableValues = {"DOCUMENT_GRANT", "DOCUMENT_REVOKE"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String type,
            @Schema(description = "USER 用户、ROLE 角色、DEPT 部门", allowableValues = {"USER", "ROLE", "DEPT"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String principalType,
            @Schema(description = "主体 ID，精确 JSON 正整数", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long principalId,
            @Schema(description = "单个例外权限位", allowableValues = {"view", "download", "edit", "comment"}, requiredMode = Schema.RequiredMode.REQUIRED)
            String permission
    ) implements Intent {
    }
}
