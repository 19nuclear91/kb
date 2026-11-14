package com.zhishiku.kb.app.notification.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 默认每 5 秒一轮；回退时以 kb.notification.derivation-enabled=false 停止派生，保留全部数据。 */
@Component
@ConditionalOnProperty(prefix = "kb.notification", name = "derivation-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDerivationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationDerivationScheduler.class);
    private final NotificationDerivationService service;
    private final GapNotificationDerivationService gaps;

    public NotificationDerivationScheduler(NotificationDerivationService service, GapNotificationDerivationService gaps) {
        this.service = service;
        this.gaps = gaps;
    }

    @Scheduled(fixedDelayString = "${kb.notification.scan-delay-ms:5000}",
            initialDelayString = "${kb.notification.initial-delay-ms:15000}")
    public void tick() {
        try {
            service.runOnce();
        } catch (RuntimeException failure) {
            log.warn("协作通知派生轮次暂不可用");
        }
        try {
            gaps.runOnce();
        } catch (RuntimeException failure) {
            log.warn("问题通知派生轮次暂不可用");
        }
    }

    @Scheduled(fixedDelayString = "${kb.notification.metrics-delay-ms:60000}",
            initialDelayString = "${kb.notification.initial-delay-ms:15000}")
    public void observe() {
        service.observe();
    }
}
