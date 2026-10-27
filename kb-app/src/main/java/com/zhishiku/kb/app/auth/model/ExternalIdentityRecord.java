package com.zhishiku.kb.app.auth.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 仅内部使用的外部身份映射。subject 绝不可序列化给客户端。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExternalIdentityRecord {
    /**
     * 绑定记录内部 ID。
     */
    private Long id;
    /**
     * 绑定的用户内部 ID。
     */
    private Long userId;
    /**
     * 认证提供方标识。
     */
    private String provider;
    /**
     * 提供方侧的外部主体标识，由 {@code @JsonIgnore} 挡住序列化。
     */
    @JsonIgnore
    private String subject;
    /**
     * 该绑定是否启用。
     */
    private Boolean enabled;
}
