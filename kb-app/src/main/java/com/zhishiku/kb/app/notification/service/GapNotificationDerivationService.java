package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.NotificationProperties;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** 有界问题派生轮次；故障只影响本来源进度，共用现有低基数指标，绝不修改源业务。 */
@Service
public class GapNotificationDerivationService {
    private static final Logger log = LoggerFactory.getLogger(GapNotificationDerivationService.class);
    private final GapNotificationDerivationTransaction transactions;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final String owner = "app-gap-" + UUID.randomUUID();

    public GapNotificationDerivationService(GapNotificationDerivationTransaction transactions, NotificationProperties properties,
                                            NotificationMetrics metrics) {
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
    }

    public int runOnce() {
        try {
            metrics.timeDiscovery(() -> transactions.discover());
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.DISCOVER);
            log.warn("问题通知派生发现暂不可用");
        }
        List<ClaimedEvent> claimed;
        try {
            claimed = transactions.claim(owner);
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.CLAIM);
            log.warn("问题通知派生领取暂不可用");
            return 0;
        }
        for (var claim : claimed) {
            process(claim);
        }
        return claimed.size();
    }

    private void process(ClaimedEvent claim) {
        if (claim.takeover()) {
            metrics.takeover();
        }
        try {
            if ("NOTE_ADDED".equals(claim.kind())) {
                transactions.ignore(claim, owner);
                return;
            }
            if (!GapNotificationDerivationTransaction.SOURCE_KINDS.contains(claim.kind())) {
                transactions.postpone(claim, owner, true);
                return;
            }
            long cursor = claim.recipientCursor();
            for (int index = 0; index < properties.getMaxBatchesPerEvent(); index++) {
                var batch = transactions.batchGap(claim, owner, cursor);
                if (batch.done()) {
                    return;
                }
                cursor = batch.cursor();
            }
            transactions.release(claim, owner);
        } catch (NotificationLeaseLost lost) {
            metrics.failure(NotificationMetrics.Stage.LEASE);
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.BATCH);
            log.warn("问题通知派生批次失败，保留待重试");
            try {
                transactions.postpone(claim, owner, false);
            } catch (RuntimeException postponeFailure) {
                metrics.failure(NotificationMetrics.Stage.POSTPONE);
            }
        }
    }
}
