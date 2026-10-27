package com.zhishiku.kb.app.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认每 5 秒一轮；回退时以 kb.notification.derivation-enabled=false 停止派生，保留全部数据。 */
@Component
@ConditionalOnProperty(prefix = "kb.notification", name = "derivation-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDerivationScheduler {
    private final NotificationDerivationService service;

    public NotificationDerivationScheduler(NotificationDerivationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${kb.notification.scan-delay-ms:5000}",
            initialDelayString = "${kb.notification.initial-delay-ms:15000}")
    public void tick() {
        service.runOnce();
    }

    @Scheduled(fixedDelayString = "${kb.notification.metrics-delay-ms:60000}",
            initialDelayString = "${kb.notification.initial-delay-ms:15000}")
    public void observe() {
        service.observe();
    }
}
