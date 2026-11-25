package com.zhishiku.kb.app.auth.model.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 唯一公开成功投影；所有业务标识显式编码为十进制字符串。 */
public final class DiagnosticViews {
    public static final String CONTRACT = "AUTHORIZATION_DIAGNOSTICS_V1";
    public static final List<String> ASSUMPTIONS = List.of("SELECTED_USER_AND_RESOURCE_ONLY", "FACTS_AT_EVALUATION",
            "LISTED_ACL_ONLY", "FILE_AVAILABILITY_NOT_CHECKED");

    private DiagnosticViews() {
    }

    @Schema(description = "目标用户最小显示信息，不附角色目录或具体密级")
    public record User(
            @Schema(description = "用户 ID，正整数的精确十进制字符串")
            String userId,
            @Schema(description = "用户显示名，按纯文本展示")
            String label
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "已核对管理资格的单个资源范围")
    public record Resource(
            @Schema(description = "路径资源类型：SPACE 空间，DOCUMENT 文档")
            String kind,
            @Schema(description = "当前所属空间 ID，精确十进制字符串")
            String spaceId,
            @Schema(description = "指定或附选文档 ID，精确十进制字符串；仅空间时为 null", nullable = true)
            String documentId
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "本资源命中的授权来源，不披露无关身份关系")
    public record Source(
            @Schema(description = "范围、主体与权限位组成的唯一引用键；全局来源固定 GLOBAL_MANAGEMENT")
            String key,
            @Schema(description = "SPACE 空间授权、DOCUMENT 文档例外或 GLOBAL_MANAGEMENT 全局管理")
            String sourceKind,
            @Schema(description = "USER 用户、ROLE 角色、DEPT 部门；全局来源为 null", nullable = true)
            String principalType,
            @Schema(description = "授权主体 ID 的精确十进制字符串；全局来源为 null", nullable = true)
            String principalId,
            @Schema(description = "来源显示名；拟议新行可只显示主体 ID")
            String label,
            @Schema(description = "独立权限位：view、download、edit、review、comment、manage")
            String permission,
            @Schema(description = "EXISTING 当前已有来源，PROPOSED 本次拟议新增来源")
            String origin
    ) {
    }

    @Schema(description = "授权命中和通用门槛分层结果，不是业务动作成功承诺")
    public record Permission(
            @Schema(description = "独立权限位；空间六项，文档不含 manage")
            String permission,
            @Schema(description = "MATCHED 命中、NOT_MATCHED 未命中、NOT_EVALUATED 未评估")
            String grantMatch,
            @Schema(description = "PASSED 通用条件通过、BLOCKED 受阻、NOT_EVALUATED 未评估")
            String scopeCondition,
            @Schema(description = "固定原因：SUBJECT_BLOCKED、SPACE_ARCHIVED、CLEARANCE_BLOCKED、AUTHORIZATION_NOT_GRANTED")
            List<String> reasonCodes,
            @Schema(description = "引用同一层 sources 中的唯一键，最多 1000 项")
            List<String> sourceKeys
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "当前发布版本权限条件，不包含版本身份或原件信息")
    public record Current(
            @Schema(description = "NOT_APPLICABLE 未选文档；NOT_EVALUATED 身份受阻；NO_CURRENT_PUBLISHED_VERSION 无当前发布版本；ASSESSED 已评估")
            String status,
            @Schema(description = "已选文档固定 CURRENT_PUBLISHED；否则为 null", nullable = true)
            String scope,
            @Schema(description = "查看条件：ALLOWED 允许、DENIED 拒绝；未评估为 null", nullable = true)
            String view,
            @Schema(description = "下载条件：ALLOWED 允许、DENIED 拒绝；未评估为 null", nullable = true)
            String download,
            @Schema(description = "查看受阻原因：SPACE_ARCHIVED、CLEARANCE_BLOCKED、VIEW_NOT_GRANTED")
            List<String> viewReasonCodes,
            @Schema(description = "下载受阻原因：查看原因及 DOWNLOAD_NOT_GRANTED")
            List<String> downloadReasonCodes
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "一个事实行集的完整分层解释")
    public record Layer(
            @Schema(description = "PASSED 账号满足条件，BLOCKED 通用账号条件未通过，不公开具体原因")
            String subjectCondition,
            @Schema(description = "空间六个独立权限的固定结果")
            List<Permission> spacePermissions,
            @Schema(description = "已选文档的五个独立权限结果；未选文档为 null", nullable = true)
            List<Permission> documentPermissions,
            @Schema(description = "当前发布版本查看和下载的条件评估")
            Current currentVersionAccess,
            @Schema(description = "去重最小来源，前后层来源并集最多 1000 条")
            List<Source> sources
    ) {
    }

    @Schema(description = "完整规范化意图中的一条拟议变化，不是写入回执")
    public record Change(
            @Schema(description = "SPACE 空间授权或 DOCUMENT 文档例外")
            String sourceKind,
            @Schema(description = "USER 用户、ROLE 角色、DEPT 部门")
            String principalType,
            @Schema(description = "输入授权主体 ID 的精确十进制字符串")
            String principalId,
            @Schema(description = "输入中的独立权限位")
            String permission,
            @Schema(description = "GRANT 拟授予、REVOKE 拟撤销")
            String action,
            @Schema(description = "ADDED 拟新增、REMOVED 拟移除、UNCHANGED 无变化")
            String outcome
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "固定版本的完整解释或预演响应")
    public record Result(
            @Schema(description = "固定 AUTHORIZATION_DIAGNOSTICS_V1")
            String contract,
            @Schema(description = "EXPLAIN 当前解释或 PREVIEW 假设预演")
            String mode,
            @Schema(description = "本次重新读取的目标用户最小信息")
            User user,
            @Schema(description = "本次明确限定的资源范围")
            Resource resource,
            @Schema(description = "同次数据库事务时间，带时区偏移的 ISO-8601 格式")
            String evaluatedAt,
            @Schema(description = "固定假设：单用户资源、本次读取、仅所列 ACL、未检查文件可用性")
            List<String> assumptions,
            @Schema(description = "当前授权事实的完整解释")
            Layer before,
            @Schema(description = "假设叠加意图后的解释；EXPLAIN 为 null", nullable = true)
            Layer after,
            @Schema(description = "PREVIEW 完整意图条目，最多 300 条；EXPLAIN 为空数组")
            List<Change> changes
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "资源上下文的最小用户候选页")
    public record Users(
            @Schema(description = "固定 AUTHORIZATION_DIAGNOSTIC_USERS_V1")
            String contract,
            @Schema(description = "本次鉴权的路径资源")
            Resource resource,
            @Schema(description = "按用户 ID 升序的候选，仅含 ID 和显示名，最多 100 条")
            List<User> items,
            @Schema(description = "是否还有下一页，不表示总人数")
            boolean hasMore,
            @Schema(description = "绑定操作者、认证代次、资源和条件的游标；末页为 null", nullable = true)
            String nextCursor
    ) {
    }
}
