package com.zhishiku.kb.common.security;

import java.util.List;

/**
 * 由服务端解析出的身份事实，供权限和配额边界使用。
 *
 * @param userId             用户内部 ID
 * @param departmentId       用户主部门内部 ID
 * @param clearanceLevel     用户密级
 * @param roleIds            用户拥有的角色 ID 列表
 * @param departmentChainIds 从主部门到根部门的部门链 ID 列表
 */
public record ResolvedPrincipal(
        long userId,
        long departmentId,
        short clearanceLevel,
        List<Long> roleIds,
        List<Long> departmentChainIds) {

    public ResolvedPrincipal {
        roleIds = roleIds == null ? List.of() : List.copyOf(roleIds);
        departmentChainIds = departmentChainIds == null ? List.of() : List.copyOf(departmentChainIds);
        if (userId <= 0 || departmentId <= 0) {
            throw new IllegalArgumentException("主体用户和部门 ID 必须为正数");
        }
        if (clearanceLevel < 0) {
            throw new IllegalArgumentException("主体密级不能为负数");
        }
    }
}
