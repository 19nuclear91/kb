package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.space.mapper.SpaceMapper;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpaceQueryServiceTest {

    private static final long USER = 7L;

    private SpaceMapper mapper;
    private PermissionMapper permissionMapper;
    private IdentityService identityService;
    private PermissionService permissionService;
    private SpaceAuthorizer authorizer;
    private SpaceQueryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(SpaceMapper.class);
        permissionMapper = mock(PermissionMapper.class);
        identityService = mock(IdentityService.class);
        permissionService = new PermissionService(permissionMapper, identityService);
        authorizer = mock(SpaceAuthorizer.class);
        service = new SpaceQueryService(mapper, permissionService, authorizer);
    }

    @Test
    @DisplayName("view 与 manage 分别解析，manage 不蕴含 view；主体只解析一次")
    void resolvesViewAndManageIndependently() {
        when(authorizer.has(Permission.SPACE_MANAGE_ALL)).thenReturn(false);
        when(identityService.resolvePrincipal(USER))
                .thenReturn(new ResolvedPrincipal(USER, 5L, (short) 2, List.of(), List.of(5L)));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_VIEW)))
                .thenReturn(List.of(1L));
        when(permissionMapper.findVisibleSpaceIds(anyList(), eq(PermissionService.PERM_MANAGE)))
                .thenReturn(List.of(2L, 1L));
        when(mapper.listVisible(List.of(1L), List.of(2L, 1L), false))
                .thenReturn(List.of(space(2L, false, false, true)));

        var result = service.list(USER);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).viewable()).isFalse();
        assertThat(result.get(0).manageable()).isTrue();
        verify(identityService, times(1)).resolvePrincipal(USER);
        verify(permissionMapper, never()).findClearanceLevel(anyLong());
        verify(permissionMapper, never()).findRoleIds(anyLong());
        verify(permissionMapper, never()).findDeptChainIds(anyLong());
        verify(permissionMapper, times(2)).findVisibleSpaceIds(anyList(), anyString());
        verify(permissionMapper, times(1)).findVisibleSpaceIds(
                anyList(), eq(PermissionService.PERM_MANAGE));
    }

    @Test
    @DisplayName("全局管理员短路为全量可见，且查询按 globalManager=true 下发")
    void globalManagerBypassesAclFiltering() {
        when(authorizer.has(Permission.SPACE_MANAGE_ALL)).thenReturn(true);
        when(mapper.listVisible(List.of(), List.of(), true))
                .thenReturn(List.of(space(1L, false, true, true)));

        assertThat(service.list(USER)).hasSize(1);
        verifyNoInteractions(permissionMapper, identityService);
    }

    @Test
    @DisplayName("既不可见也不可管理时返回 404，不泄露空间存在性")
    void invisibleSpaceIsReportedAsNotFound() {
        when(authorizer.has(Permission.SPACE_MANAGE_ALL)).thenReturn(false);
        when(identityService.resolvePrincipal(USER))
                .thenThrow(IdentityException.disabled("用户已停用"));
        when(mapper.findById(anyLong(), any(), any(), anyBoolean())).thenReturn(null);

        assertThatThrownBy(() -> service.get(USER, 99L))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("空间不存在");
        verify(permissionMapper, never()).findVisibleSpaceIds(anyList(), anyString());
    }

    private static SpaceRecord space(Long id, boolean archived, boolean viewable, boolean manageable) {
        SpaceRecord record = new SpaceRecord();
        record.setId(id);
        record.setCode("space-" + id);
        record.setName("空间" + id);
        record.setArchived(archived);
        record.setVersion(0L);
        record.setViewable(viewable);
        record.setManageable(manageable);
        return record;
    }
}
