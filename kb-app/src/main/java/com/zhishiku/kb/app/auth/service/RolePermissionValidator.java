package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Flyway 完成后、管理员引导前检查绑定目录；有意收窄管理员权限仅告警。
 */
@Component
@Order(0)
public class RolePermissionValidator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RolePermissionValidator.class);
    private final RolePermissionMapper mapper;

    public RolePermissionValidator(RolePermissionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        var unknown = mapper.findAllPermissionCodes().stream().filter(code -> !Permission.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("角色绑定了未知功能权限：" + unknown);
        }
        var admin = mapper.findPermissionsByRoleCode("ROLE_ADMIN");
        var missing = Permission.codes().stream().filter(code -> !admin.contains(code)).toList();
        if (!missing.isEmpty()) log.warn("ROLE_ADMIN 未绑定全部功能权限，不自动修复，缺少：{}", missing);
    }
}
