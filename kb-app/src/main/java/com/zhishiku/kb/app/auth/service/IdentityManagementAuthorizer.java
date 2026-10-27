package com.zhishiku.kb.app.auth.service;

import cn.dev33.satoken.exception.SaTokenException;
import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 身份与部门管理操作的唯一授权入口。
 */
@Component
public class IdentityManagementAuthorizer {

    private final RolePermissionMapper mapper;

    public IdentityManagementAuthorizer(RolePermissionMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    public long require(Permission permission) {
        long userId = requireLogin();
        if (!holds(userId, permission)) {
            throw IdentityException.forbidden("缺少功能权限：" + permission.code());
        }
        return userId;
    }

    /**
     * 只要求已登录，返回服务端解析出的用户 ID。
     *
     * <p>供需要"先拿到身份、再按资源判定资格"的调用方使用（例如空间级委派）。
     * <b>它本身不是授权</b>——调用方必须紧接着做资源级判定。
     */
    public long requireLogin() {
        try {
            StpUtil.checkLogin();
            return StpUtil.getLoginIdAsLong();
        } catch (SaTokenException | NumberFormatException e) {
            throw IdentityException.forbidden("未登录或登录状态无效");
        }
    }

    /**
     * 每次从持久化绑定解析权限，停用过滤在查询侧，不写入共享会话。
     */
    public boolean has(Permission permission) {
        try {
            return StpUtil.isLogin() && holds(StpUtil.getLoginIdAsLong(), permission);
        } catch (SaTokenException | NumberFormatException e) {
            return false;
        }
    }

    /**
     * 用主体句柄判定功能权限：权限码在句柄内只查询一次，供同一调用栈内
     * 多次判定的路径复用；句柄已代表服务端解析过的身份，故不再经过会话查询。
     *
     * <p>失败态的句柄不持有任何功能权限（拒绝默认值）；需要区分"停用"与
     * "无权限"的调用方应先调用 {@code handle.requireEnabled()}。
     */
    public boolean has(PermissionService.PrincipalHandle handle, Permission permission) {
        Objects.requireNonNull(handle, "主体句柄不可为 null");
        Objects.requireNonNull(permission);
        return handle.functionalPermissions().contains(permission.code());
    }

    private boolean holds(long userId, Permission permission) {
        Objects.requireNonNull(permission);
        var codes = mapper.findUserPermissions(userId);
        return codes != null && codes.contains(permission.code());
    }
}
