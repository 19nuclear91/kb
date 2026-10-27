package com.zhishiku.kb.app.space;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.PermissionService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 空间范围解析保留归档过滤并放过 manage；文档例外路径另有独立归档检查。
 *
 * <p>行为层面的断言在 {@code SpaceAuthorizationPostgresSmokeTest} 与
 * {@code db/verify_permission.sql} 里对真实 ParadeDB 执行；这里守的是
 * SQL 本身不被改回去——它是整个系统的安全边界，改错不会报错，只会让人
 * 看到不该看的东西。
 */
class PermissionMapperArchiveFilterTest {

    private static String visibleSpaceSql() throws Exception {
        Select select = PermissionMapper.class
                .getMethod("findVisibleSpaceIds", List.class, String.class)
                .getAnnotation(Select.class);
        assertThat(select).as("findVisibleSpaceIds 的 @Select 注解").isNotNull();
        return String.join("\n", select.value());
    }

    @Test
    @DisplayName("可见空间查询必须关联 kb_space 并排除已归档空间")
    void visibleSpaceQueryExcludesArchivedSpaces() throws Exception {
        String sql = visibleSpaceSql();

        assertThat(sql).contains("JOIN kb_space s ON s.id = a.space_id");
        assertThat(sql).contains("s.archived = false");
    }

    @Test
    @DisplayName("manage 不受归档过滤影响，否则归档后无人能取消归档")
    void manageIsExemptFromArchiveFilter() throws Exception {
        String sql = visibleSpaceSql();

        assertThat(sql)
                .as("归档条件必须对 manage 放行")
                .contains("(s.archived = false OR a.permission = '" + PermissionService.PERM_MANAGE + "')");
    }
}
