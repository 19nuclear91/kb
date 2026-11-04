package com.zhishiku.kb.app.auth.service;

/**
 * 审计操作域。{@code name()} 即持久化到 {@code sys_identity_audit.operation_scope} 的值。
 *
 * <p><b>为什么必须是枚举而不是字符串</b>：审计的幂等键是
 * {@code UNIQUE (operation_scope, request_id)}，而 {@code request_id} 完全由客户端提供，
 * 服务端只校验非空。于是裸操作域（例如 {@code "CREATE"}）会让两个不同实体的写操作
 * 共用同一个幂等命名空间——一次空间创建撞上某次部门创建的 request_id 时，
 * 会被判定为重试，按既有实现返回那条审计事件记录的 targetId，
 * 也就是把一个部门 ID 当成空间 ID 查出来返回；而请求的空间根本没有被创建，
 * 调用方却收到成功响应。
 *
 * <p>因此每个操作域<b>必须带实体前缀</b>。用枚举而非常量字符串，是为了让
 * "传一个裸字符串进去"在编译期就不可能发生，而不是靠约定和评审。
 * 新增实体时沿用同一规则：{@code <实体>_<操作>}。
 */
public enum AuditScope {

    USER_MOVE_DEPARTMENT("user"),
    USER_ENABLE("user"),
    USER_DISABLE("user"),

    /**
     * 用户密级调整。升级与降级共用这一个操作域。
     *
     * <p>分成 {@code USER_CLEARANCE_RAISE} / {@code USER_CLEARANCE_LOWER} 会把方向
     * 写进幂等键：同一个 {@code request_id} 的重试若被判成反方向，就不再命中首次结果，
     * 于是一次重试变成一次新的写入。方向本来就能从审计明细里的前后密级读出来，
     * 不需要也不应该参与幂等判定。
     */
    USER_CLEARANCE_CHANGE("user"),

    /**
     * 自助改密（设计 D5）。request_id 客户端可选：缺省由服务端生成 UUID；
     * 客户端重试时携带首次的请求标识即可命中幂等直接返回，否则会因为密码
     * 已经变更而在"原密码错误"上被二次拒绝，把一次成功的改密报成失败。
     */
    USER_PASSWORD_CHANGE("user"),

    /**
     * 管理员重置密码（任务 4.5）。与 {@link #USER_PASSWORD_CHANGE} 分开而不是共用：
     * 自助改密校验原密码、清除强制改密标记；重置不校验原密码、置上强制改密标记，
     * 两者的请求标识来自不同的调用方。共用一个域会让两条路径的幂等命名空间互相
     * 干扰——一次管理端重试可能命中自助改密的记录，把首次结果对错人。
     */
    USER_PASSWORD_RESET("user"),

    /**
     * 建号（设计 D9）。重放经审计取 {@code targetId} 返回既有用户：
     * 请求标识由客户端提供，重试必须拿回首次创建的用户，而不是撞唯一约束的 409。
     */
    USER_CREATE("user"),

    /**
     * 分配角色（任务 4.3）。重复分配（{@code ON CONFLICT DO NOTHING}）仍是成功，
     * 审计明细的 {@code created} 字段记录本次是否真正新建了绑定。
     */
    USER_ROLE_ASSIGN("user"),

    /**
     * 解除角色（任务 4.3）。未绑定返回 404，不写审计。
     */
    USER_ROLE_REVOKE("user"),

    /**
     * 角色行创建（任务 5.2 引导）。这是系统内第一条创建角色行的写路径：
     * 角色目录在此之前只读，空库引导需要先建出 ROLE_ADMIN 行本身。
     * 目标实体是 role，与"给用户绑定角色"（{@link #USER_ROLE_ASSIGN}）分开——
     * 前者回答"角色行何时被创建"，后者回答"谁在何时被授予了角色"。
     */
    ROLE_CREATE("role"),
    ROLE_RENAME("role"),
    ROLE_DELETE("role"),
    ROLE_PERMISSION_GRANT("role"),
    ROLE_PERMISSION_REVOKE("role"),

    EXTERNAL_BIND("external-identity"),
    EXTERNAL_UNBIND("external-identity"),

    DEPARTMENT_CREATE("department"),
    DEPARTMENT_RENAME("department"),
    DEPARTMENT_MOVE("department"),
    DEPARTMENT_ENABLE("department"),
    DEPARTMENT_DISABLE("department"),

    SPACE_CREATE("space"),
    SPACE_UPDATE("space"),
    SPACE_PUBLICATION_POLICY("space"),
    SPACE_ARCHIVE("space"),
    SPACE_UNARCHIVE("space"),
    SPACE_DELETE("space"),

    SPACE_ACL_GRANT("space-acl"),
    SPACE_ACL_BATCH_GRANT("space-acl"),
    SPACE_ACL_REVOKE("space-acl"),

    DOC_ACL_GRANT("doc-acl"),
    DOC_ACL_REVOKE("doc-acl"),

    DOCUMENT_RELOCATE("document"),

    /**
     * 管理端整文档删除。用户端 {@code DELETE /api/documents/{id}} 按 edit 判定且不写审计；
     * 管理端按所属空间 manage 判定，是管理员对他人内容的破坏性操作，必须留痕并以
     * {@code request_id} 幂等。快照只含文档 ID、所属空间与归档状态，不含标题或存储键。
     */
    DOCUMENT_ADMIN_DELETE("document"),
    DOCUMENT_METADATA_UPDATE("document"),
    DOCUMENT_CATEGORY_CREATE("document-category"),
    DOCUMENT_CATEGORY_UPDATE("document-category"),
    DOCUMENT_CATEGORY_DELETE("document-category"),
    DOCUMENT_TAG_CREATE("document-tag"),
    DOCUMENT_TAG_UPDATE("document-tag"),
    DOCUMENT_SECURITY_LEVEL_CHANGE("document"),

    PARSE_DEAD_LETTER_QUERY("PARSE_DEAD_LETTER"),
    PARSE_DEAD_LETTER_REPLAY("PARSE_DEAD_LETTER"),

    /**
     * 登录成功与失败。request_id 由服务端生成（客户端没有请求标识）；
     * 目标实体是 auth 而不是 user：登录事件回答"谁在何时尝试登录"，
     * 与用户生命周期事件分开检索。
     */
    AUTH_LOGIN_SUCCESS("auth"),
    AUTH_LOGIN_FAILURE("auth"),

    /**
     * 企业浏览器登录，仅服务端 flowId 作为请求标识。
     */
    SSO_LOGIN("user"),
    /**
     * 已验证本人的外部绑定，与管理员原始管理事件隔离。
     */
    SSO_BIND("user");

    private final String targetType;

    AuditScope(String targetType) {
        this.targetType = targetType;
    }

    /**
     * 审计事件的目标实体类型，写入 {@code target_type}。
     *
     * <p>由操作域自带而不是由调用方另传一个字符串，这样操作域与目标类型
     * 不可能对不上——那种不一致会让按目标检索审计时漏掉事件。
     */
    public String targetType() {
        return targetType;
    }
}
