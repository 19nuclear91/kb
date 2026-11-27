package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.service.impl.subscription.KnowledgeDerivationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅两个派生开关均开启时注册，发布上下文与个人操作不受此调度器影响。 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"kb.notification.derivation-enabled", "kb.knowledge-subscriptions.derivation-enabled"},
        havingValue = "true", matchIfMissing = true)
public class KnowledgeSubscriptionScheduler {
    private final KnowledgeDerivationService service;

    @Scheduled(fixedDelayString = "${kb.knowledge-subscriptions.scan-delay-ms:5000}")
    public void run() {
        service.runOnce();
    }
}
