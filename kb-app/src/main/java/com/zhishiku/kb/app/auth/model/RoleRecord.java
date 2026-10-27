package com.zhishiku.kb.app.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色目录条目的持久化投影。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleRecord {
    /**
     * 角色内部 ID。
     */
    private Long id;
    /**
     * 角色编码，例如 {@code ROLE_ADMIN}。
     */
    private String code;
    /**
     * 角色名称。
     */
    private String name;
}
