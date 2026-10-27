package com.zhishiku.kb.app.space.model;

import com.zhishiku.kb.common.security.PrincipalType;

/**
 * 空间授权行。{@code principalKey} 是 DDL 生成列的投影，用于响应与审计。
 */
public class SpaceAclRecord {

    private Long id;
    private Long spaceId;
    private Short principalType;
    private Long principalId;
    private String permission;
    private String principalKey;
    private String principalName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public Short getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(Short principalType) {
        this.principalType = principalType;
    }

    public Long getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(Long principalId) {
        this.principalId = principalId;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public String getPrincipalKey() {
        return principalKey;
    }

    public void setPrincipalKey(String principalKey) {
        this.principalKey = principalKey;
    }

    public String getPrincipalName() {
        return principalName;
    }

    public void setPrincipalName(String principalName) {
        this.principalName = principalName;
    }

    public PrincipalType principalTypeEnum() {
        for (PrincipalType type : PrincipalType.values()) {
            if (Short.valueOf(type.code()).equals(principalType)) return type;
        }
        throw new IllegalStateException("未知主体类型: " + principalType);
    }
}
