package com.zhishiku.kb.app.auth.model;

import java.util.Arrays;
import java.util.List;

/**
 * 功能权限目录。冒号仅用于命名，权限之间没有继承或通配关系。
 */
public enum Permission {
    IDENTITY_USER_READ("identity:user:read", "查询用户"),
    IDENTITY_USER_WRITE("identity:user:write", "创建用户、调整部门与启停用户"),
    IDENTITY_USER_PASSWORD("identity:user:password", "重置用户密码"),
    IDENTITY_CLEARANCE_WRITE("identity:clearance:write", "调整用户密级"),
    IDENTITY_ROLE_READ("identity:role:read", "查询角色与权限目录"),
    IDENTITY_ROLE_ASSIGN("identity:role:assign", "分配与解除用户角色"),
    IDENTITY_ROLE_WRITE("identity:role:write", "创建、改名、删除角色与变更角色权限"),
    IDENTITY_DEPT_READ("identity:dept:read", "查询部门"),
    IDENTITY_DEPT_WRITE("identity:dept:write", "创建、改名、移动与启停部门"),
    IDENTITY_EXTERNAL_WRITE("identity:external:write", "绑定与解除外部身份"),
    IDENTITY_EXTERNAL_READ("identity:external:read", "查询脱敏外部身份绑定"),
    IDENTITY_LIFECYCLE_READ("identity:lifecycle:read", "查询员工受管状态与生命周期处置记录"),
    IDENTITY_LIFECYCLE_MANAGE("identity:lifecycle:manage", "管理员工映射与重试原离职事件"),
    IDENTITY_LIFECYCLE_RESTORE("identity:lifecycle:restore", "核对并确认员工复职"),
    IDENTITY_EMERGENCY_MANAGE("identity:emergency:manage", "登记与轮换独立本地应急管理员"),
    IDENTITY_AUDIT_READ("identity:audit:read", "查询身份审计"),
    AUDIT_READ("audit:read", "跨目标查询管理与登录审计"),
    AI_USAGE_READ("ai:usage:read", "跨部门查询聊天用量统计"),
    AI_AUDIT_READ("ai:audit:read", "跨部门查询聊天调用审计"),
    OPERATIONS_REPORTS_READ("operations:reports:read", "查询全局安全运营汇总，不授予资源明细或 AI 读取资格"),
    KNOWLEDGE_GAP_READ("knowledge:gap:read", "读取统一知识问题受理队列，不授予文档读取资格"),
    KNOWLEDGE_GAP_ASSIGN("knowledge:gap:assign", "为当前可访问的知识问题分派合格处理人"),
    KNOWLEDGE_GAP_HANDLE("knowledge:gap:handle", "处理当前分派给本人的知识问题，不授予全局问题读取资格"),
    KNOWLEDGE_GAP_CLOSE("knowledge:gap:close", "带原因关闭当前可访问的知识问题，不代替本人确认解决"),
    PARSE_DEADLETTER_READ("parse:deadletter:read", "查询解析死信"),
    PARSE_DEADLETTER_REPLAY("parse:deadletter:replay", "重放解析死信"),
    SPACE_CREATE("space:create", "创建空间"),
    SPACE_MANAGE_ALL("space:manage-all", "绕过空间 ACL 管理所有空间"),
    SPACE_GRANT_MANAGE("space:grant-manage", "授予与撤销空间 manage 授权"),
    DOCUMENT_SECURITY_LEVEL_WRITE("document:security-level:write", "查询和调整文档密级"),
    DOCUMENT_TAG_READ("document:tag:read", "读取全局标签管理目录"),
    DOCUMENT_TAG_WRITE("document:tag:write", "创建、改名和启停全局标签"),
    EMBEDDING_READ("embedding:read", "查询向量配置与重建状态"),
    EMBEDDING_REBUILD("embedding:rebuild", "发起与恢复向量重建");

    private final String code;
    private final String description;

    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Permission::code).toList();
    }

    public static boolean contains(String code) {
        return codes().contains(code);
    }
}
