package com.zhishiku.kb.app.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 用户身份的持久化投影。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdentityRecord {
    /**
     * 用户内部 ID。
     */
    private Long id;
    /**
     * 登录用户名，全库唯一。
     */
    private String username;
    /**
     * 展示名称。
     */
    private String displayName;
    /**
     * 主部门内部 ID。
     */
    private Long departmentId;
    /**
     * 用户密级，取值来自服务端密级字典。
     */
    private Short clearanceLevel;
    /**
     * 用户是否启用。
     */
    private Boolean enabled;
    /**
     * 乐观锁版本，随归属、启停、密级变更递增。
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
