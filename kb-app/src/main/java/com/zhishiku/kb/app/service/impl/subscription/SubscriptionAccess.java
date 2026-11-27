package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionQuery;
import com.zhishiku.kb.app.domain.dto.subscription.SubscriptionTarget;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import lombok.RequiredArgsConstructor;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;

/** 每次访问从可信用户解析当前资格，锁等待后清除会话级查询缓存再核对。 */
@Service
@RequiredArgsConstructor
public class SubscriptionAccess {
    private final PermissionService permissions;
    private final SubscriptionQueryMapper queries;
    private final SqlSessionTemplate sessions;

    public PermissionScope current(long actor) {
        sessions.clearCache();
        var handle = permissions.principalOf(actor);
        handle.requireEnabled();
        return permissions.resolve(handle, PermissionService.PERM_VIEW);
    }

    public SubscriptionTarget target(PermissionScope scope, SubscriptionKind kind, long targetId, boolean allowDisabled) {
        return queries.target(new SubscriptionQuery(scope.userId(), scope.visibleSpaceIdList(),
                Arrays.asList(scope.principalKeys()), scope.clearanceLevel(), kind, targetId,
                null, null, null, null, 1, allowDisabled));
    }

    public void requireTarget(long actor, SubscriptionKind kind, long targetId) {
        if (target(current(actor), kind, targetId, false) == null) {
            throw SubscriptionFailure.hidden();
        }
    }

    public void lockTarget(SubscriptionKind kind, long targetId) {
        if (queries.lockTarget(kind, targetId) == null) {
            throw SubscriptionFailure.hidden();
        }
    }
}
