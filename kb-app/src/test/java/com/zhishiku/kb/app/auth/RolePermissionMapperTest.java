package com.zhishiku.kb.app.auth;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionMapperTest {
    @Test
    void managementLockUsesOneFixedTransactionKeyAndQueryFiltersDisabledUsers() throws Exception {
        var type = IdentityDepartmentMapper.class;
        String lock = String.join(" ", type.getMethod("lockManagement").getAnnotation(Select.class).value());
        assertThat(lock).isEqualTo("SELECT 1 FROM pg_advisory_xact_lock(741306011)").doesNotContain("#{");
        String query = String.join(" ",
                type.getMethod("findEnabledManagementPermissions").getAnnotation(Select.class).value());
        assertThat(query).contains("u.enabled = true", "ur.user_id", "ur.role_id", "sys_role_permission",
                "identity:role:assign", "identity:role:write").doesNotContain("ROLE_ADMIN");
    }

    @Test
    void permissionUnionAndDisabledUserFilterAreAppliedByQuery() throws Exception {
        String sql = String.join(" ", RolePermissionMapper.class
                .getMethod("findUserPermissions", long.class).getAnnotation(Select.class).value());
        assertThat(sql).contains("SELECT DISTINCT rp.permission", "JOIN sys_user_role ur",
                "JOIN sys_role_permission rp", "ur.user_id = u.id", "rp.role_id = ur.role_id",
                "u.id = #{userId}", "u.enabled = true");
    }
}
