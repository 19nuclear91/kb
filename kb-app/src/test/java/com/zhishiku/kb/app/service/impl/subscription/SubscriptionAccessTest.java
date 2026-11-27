package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionQuery;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionTarget;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionAccessTest {
    @Test
    void eachTargetUsesFreshCurrentViewScopeAndDisabledTagOnlyAppearsInOwnersManagement() {
        var permissions = mock(PermissionService.class);
        var handle = mock(PermissionService.PrincipalHandle.class);
        var queries = mock(SubscriptionQueryMapper.class);
        var session = mock(SqlSessionTemplate.class);
        var access = new SubscriptionAccess(permissions, queries, session);
        when(permissions.principalOf(7)).thenReturn(handle);
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(), List.of("1:7")));
        // 仓储返回文档例外/候选版本可读的目标；业务不能用manage替换view或去掉主体/密级。
        when(queries.target(any())).thenAnswer(call -> {
            SubscriptionQuery query = call.getArgument(0);
            assertThat(query.actor()).isEqualTo(7);
            assertThat(query.principalKeys()).containsExactly("1:7");
            assertThat(query.clearance()).isEqualTo((short) 2);
            if (query.kind() == SubscriptionKind.TAG && !query.allowDisabled()) {
                return null;
            }
            return new SubscriptionTarget(10, "授权目标", query.kind() != SubscriptionKind.TAG, null, null, 0, null);
        });
        access.requireTarget(7, SubscriptionKind.DOCUMENT, 10);
        assertThatThrownBy(() -> access.requireTarget(7, SubscriptionKind.TAG, 10)).isInstanceOf(SubscriptionFailure.class);
        assertThat(access.target(access.current(7), SubscriptionKind.TAG, 10, true).enabled()).isFalse();
        when(permissions.resolve(handle, "view")).thenReturn(PermissionScope.of(7, (short) 2, List.of(10L), List.of("1:7")));
        access.requireTarget(7, SubscriptionKind.SPACE, 10);
        verify(session, times(4)).clearCache();
        verify(handle, times(4)).requireEnabled();
        verify(permissions, never()).resolve(any(PermissionService.PrincipalHandle.class), eq("manage"));
    }
}
