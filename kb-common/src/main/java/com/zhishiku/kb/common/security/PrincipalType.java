package com.zhishiku.kb.common.security;

/**
 * 授权主体类型。
 *
 * <p>数值与 DDL 中 {@code kb_space_acl.principal_type} / {@code kb_document_acl.principal_type}
 * 的 CHECK 约束一一对应，<b>不可随意调整</b>。
 *
 * <p>见《架构决策记录.md》4.1。
 */
public enum PrincipalType {

    /**
     * 直接授予某个用户
     */
    USER((short) 1),

    /**
     * 授予某个角色，拥有该角色的用户全部命中
     */
    ROLE((short) 2),

    /**
     * 授予某个部门。语义为<b>向下</b>生效：
     * 授权给「研发中心」，其整棵子树（平台组、算法组……）的用户自动命中。
     * 不会向上传递给上级部门。
     */
    DEPT((short) 3);

    private final short code;

    PrincipalType(short code) {
        this.code = code;
    }

    public short code() {
        return code;
    }
}
