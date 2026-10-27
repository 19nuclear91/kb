package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.common.security.ResolvedPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 解析真实主体句柄，持久层权限与归档行为使用内存替身，不验证数据库查询执行。 */
class CommentPermissionScopeTest {
    @Test
    void commentUsesUserRoleAndAncestorDepartmentKeysWithoutImplyingAnyOtherBit() {
        var mapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 9L, (short) 2,
                List.of(4L), List.of(1L, 8L, 9L)));
        for (String grant : List.of("1:7", "2:4", "3:1", "3:8", "3:9")) {
            when(mapper.findVisibleSpaceIds(anyList(), anyString())).thenAnswer(call -> {
                List<String> keys = call.getArgument(0);
                return "comment".equals(call.getArgument(1)) && keys.contains(grant) ? List.of(3L) : List.of();
            });
            var service = new PermissionService(mapper, identities);
            assertThat(service.resolve(7, PermissionService.PERM_COMMENT).visibleSpaceIdList()).as(grant).containsExactly(3L);
            for (String bit : List.of("view", "download", "edit", "review", "manage")) {
                assertThat(service.resolve(7, bit).visibleSpaceIdList()).as(bit).isEmpty();
            }
        }
    }

    @Test
    void viewReviewOrManageNeverImplyCommentAndArchivePausesComment() {
        var mapper = mock(PermissionMapper.class);
        var identities = mock(IdentityService.class);
        var archived = new AtomicBoolean(false);
        when(identities.resolvePrincipal(7)).thenReturn(new ResolvedPrincipal(7, 9L, (short) 2, List.of(), List.of(9L)));
        when(mapper.findVisibleSpaceIds(anyList(), anyString())).thenAnswer(call -> {
            String bit = call.getArgument(1);
            if ("manage".equals(bit)) {
                return List.of(3L);
            }
            if (archived.get()) {
                return List.of();
            }
            return List.of("view", "review", "comment").contains(bit) ? List.of(3L) : List.of();
        });
        var service = new PermissionService(mapper, identities);
        assertThat(service.resolve(7, "comment").visibleSpaceIdList()).containsExactly(3L);
        archived.set(true);
        assertThat(service.resolve(7, "comment").visibleSpaceIdList()).isEmpty();
        assertThat(service.resolve(7, "view").visibleSpaceIdList()).isEmpty();
        assertThat(service.resolve(7, "manage").visibleSpaceIdList()).containsExactly(3L);
        when(mapper.findVisibleSpaceIds(anyList(), anyString())).thenAnswer(call ->
                List.of("view", "review", "manage").contains((String) call.getArgument(1)) ? List.of(3L) : List.of());
        assertThat(service.resolve(7, "comment").visibleSpaceIdList()).isEmpty();
    }
}
