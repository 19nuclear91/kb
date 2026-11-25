package com.zhishiku.kb.app.auth.model.diagnostics;

/** 仅在 app 内流转的事实，控制器不能直接序列化此类型。 */
public final class DiagnosticFacts {
    private DiagnosticFacts() {
    }

    /** kb_space_acl／kb_document_acl 命中行的最小投影。 */
    public record Acl(
            /** SPACE 空间 ACL 或 DOCUMENT 文档例外 ACL。 */
            String sourceKind,
            /** 主体类型：1 用户、2 角色、3 部门。 */
            int principalType,
            /** sys_user、sys_role 或 sys_dept 的关联 ID。 */
            long principalId,
            /** 持久主体键：类型数值与主体 ID。 */
            String principalKey,
            /** 当前命中主体的显示名；缺失时使用主体 ID。 */
            String label,
            /** 独立权限位：view、download、edit、review、comment、manage。 */
            String permission,
            /** true 为预演拟议新增行，false 为当前持久行。 */
            boolean proposed
    ) {
        public String key() {
            return sourceKind + ":" + principalKey + ":" + permission;
        }
    }

    public record Grant(
            String permission,
            boolean spaceGranted,
            boolean documentGranted
    ) {
    }

    public record Current(
            boolean hasCurrent,
            boolean validCurrent,
            boolean clearancePassed,
            boolean viewAllowed,
            boolean downloadAllowed
    ) {
    }
}
