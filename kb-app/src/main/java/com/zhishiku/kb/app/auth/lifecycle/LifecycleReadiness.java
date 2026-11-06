package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.model.ManagementChange;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import com.zhishiku.kb.common.security.IdentityConflictReason;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 接收器创建及重连前也须调用；持久保护不依赖这个开关。 */
@Component
public class LifecycleReadiness implements ApplicationRunner {
    private final ManagementInvariant management;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public LifecycleReadiness(ManagementInvariant management, PlatformTransactionManager manager,
                              @Value("${kb.identity.lifecycle.dingtalk.enabled:false}") boolean enabled) {
        this.management = management;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (enabled) {
            requireReady();
        }
    }

    public void requireReady() {
        transaction.executeWithoutResult(status -> {
            management.lock();
            if (!management.emergencyRemainsAfter(ManagementChange.unchanged())) {
                throw IdentityException.conflict(IdentityConflictReason.EMERGENCY_MANAGER_REQUIRED,
                        "启用离职接收前必须登记合格的独立本地应急管理员");
            }
        });
    }
}
