package com.zhishiku.kb.app.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 部门节点的持久化投影。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentRecord {
    /**
     * 部门内部 ID。
     */
    private Long id;
    /**
     * 上级部门内部 ID；根部门为 {@code null}。
     */
    private Long parentId;
    /**
     * 部门编码，全库唯一。
     */
    private String code;
    /**
     * 部门名称。
     */
    private String name;
    /**
     * 物化祖先路径，形如 {@code /1/2/}。
     */
    private String path;
    /**
     * 是否启用。
     */
    private Boolean enabled;
    /**
     * 乐观锁版本，随重命名、移动、启停递增。
     */
    private Long version;
    /**
     * 创建时间。
     */
    private Instant createdAt;
    /**
     * 最后更新时间。
     */
    private Instant updatedAt;
}
