package com.zhishiku.kb.app.auth.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 本地凭据投影：按用户名登录时取哈希与强制改密标记，建号时作为插入参数。
 *
 * <p>刻意不用 {@code @Data}：Lombok 生成的 toString 会把 passwordHash 拼进去，
 * 任何一次误打日志都是一次凭据泄露。这里只要 getter/setter。
 */
@Getter
@Setter
@NoArgsConstructor
public class LocalCredentialRecord {
    /**
     * 用户内部 ID。
     */
    private Long id;
    /**
     * 登录用户名。
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
     * 用户密级。
     */
    private Short clearanceLevel;
    /**
     * 密码的自适应哈希；绝不明文存储，也不进日志。
     */
    private String passwordHash;
    /**
     * 是否必须先完成改密才能访问其他业务接口。
     */
    private Boolean mustChangePassword;
}
