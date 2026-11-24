package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.model.DepartmentRecord;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TermAccessTest {
    @Test
    void everyAccessAndLockRecheckClearsCacheBeforeReloadingCurrentIdentity() {
        var current = mock(AdminQueryAccess.class);
        var cache = mock(SqlSessionTemplate.class);
        var actor = mock(PermissionService.PrincipalHandle.class);
        when(actor.userId()).thenReturn(Long.MAX_VALUE);
        when(actor.functionalPermissions()).thenReturn(Set.of("search:term:read", "search:term:write"));
        when(current.current()).thenReturn(actor);
        var access = new TermAccess(current, cache);
        assertThat(access.requireRead()).isEqualTo(Long.MAX_VALUE);
        assertThat(access.requireWrite()).isEqualTo(Long.MAX_VALUE);
        access.recheckWriter(Long.MAX_VALUE);
        var order = inOrder(current, cache);
        for (int i = 0; i < 3; i++) {
            order.verify(cache).clearCache();
            order.verify(current).current();
        }
        when(actor.functionalPermissions()).thenReturn(Set.of("search:term:read"));
        assertThatThrownBy(() -> access.recheckWriter(Long.MAX_VALUE)).isInstanceOfSatisfying(TermException.class,
                error -> assertThat(error.reason()).isEqualTo(TermException.Reason.FORBIDDEN));
        when(actor.functionalPermissions()).thenReturn(Set.of("search:term:write"));
        when(actor.userId()).thenReturn(8L);
        assertThatThrownBy(() -> access.recheckWriter(Long.MAX_VALUE)).isInstanceOf(TermException.class);
    }

    @Test
    void disabledIdentityPasswordGateAndMissingSubjectAreExplicitlyDeniedButLoginRemains401() {
        var current = mock(AdminQueryAccess.class);
        var access = new TermAccess(current, mock(SqlSessionTemplate.class));
        for (RuntimeException denied : new RuntimeException[]{QueryException.forbidden(), IdentityException.disabled("失效"),
                IdentityException.notFound("不存在")}) {
            doThrow(denied).when(current).current();
            assertThatThrownBy(access::requireRead).isInstanceOfSatisfying(TermException.class,
                    error -> assertThat(error.reason()).isEqualTo(TermException.Reason.FORBIDDEN));
        }
        IdentityException login = IdentityException.unauthorized("失效");
        doThrow(login).when(current).current();
        assertThatThrownBy(access::requireWrite).isSameAs(login);
        var actor = mock(PermissionService.PrincipalHandle.class);
        doReturn(actor).when(current).current();
        doThrow(IdentityException.disabled("失效")).when(actor).requireEnabled();
        assertThatThrownBy(access::requireRead).isInstanceOf(TermException.class);
    }

    @Test
    void existingIdentityResolverRejectsInactiveSubjectOrMissingInactivePrimaryDepartment() {
        var identities = mock(IdentityDepartmentMapper.class);
        var service = new IdentityService(identities);
        when(identities.findIdentity(7)).thenReturn(null);
        assertThatThrownBy(() -> service.resolvePrincipal(7)).isInstanceOf(IdentityException.class);
        when(identities.findIdentity(7)).thenReturn(user(false, 5L));
        assertThatThrownBy(() -> service.resolvePrincipal(7)).isInstanceOf(IdentityException.class);
        when(identities.findIdentity(7)).thenReturn(user(true, null));
        assertThatThrownBy(() -> service.resolvePrincipal(7)).isInstanceOf(IdentityException.class);
        when(identities.findIdentity(7)).thenReturn(user(true, 5L));
        when(identities.findDepartment(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> service.resolvePrincipal(7)).isInstanceOf(IdentityException.class);
        when(identities.findDepartment(5)).thenReturn(new DepartmentRecord(5L, null, "DEPT", "部门", "/5/", false, 0L, null, null));
        assertThatThrownBy(() -> service.resolvePrincipal(7)).isInstanceOf(IdentityException.class);
    }

    private IdentityRecord user(boolean enabled, Long department) {
        return new IdentityRecord(7L, "user", "用户", department, (short) 1, enabled, 0L, null, null);
    }
}
