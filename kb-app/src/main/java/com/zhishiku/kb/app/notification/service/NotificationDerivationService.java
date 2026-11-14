package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.NotificationProperties;
import com.zhishiku.kb.app.notification.mapper.NotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import com.zhishiku.kb.app.notification.model.NotificationType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 有界派生轮次：发现 → 领取 → 分批提交。本类不开启事务，每一步经事务代理独立提交。
 * 派生失败只保留待处理工作并退避，不撤销源审核或评论。日志只写固定消息，不带标识。
 */
@Service
public class NotificationDerivationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDerivationService.class);
    private final NotificationDerivationTransaction transactions;
    private final NotificationDerivationMapper mapper;
    private final NotificationTargetRegistry registry;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final String owner = "app-" + UUID.randomUUID();

    public NotificationDerivationService(@Qualifier("notificationDerivationTransaction") NotificationDerivationTransaction transactions,
                                         @Qualifier("notificationDerivationMapper") NotificationDerivationMapper mapper,
                                         NotificationTargetRegistry registry, NotificationProperties properties,
                                         NotificationMetrics metrics) {
        this.transactions = transactions;
        this.mapper = mapper;
        this.registry = registry;
        this.properties = properties;
        this.metrics = metrics;
    }

    public String owner() {
        return owner;
    }

    /** @return 本轮领取的事件数 */
    public int runOnce() {
        try {
            metrics.timeDiscovery(() -> transactions.discover());
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.DISCOVER);
            log.warn("通知派生发现暂不可用");
        }
        List<ClaimedEvent> claimed;
        try {
            claimed = transactions.claim(owner);
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.CLAIM);
            log.warn("通知派生领取暂不可用");
            return 0;
        }
        for (var claim : claimed) {
            process(claim);
        }
        return claimed.size();
    }

    public void observe() {
        try {
            var backlog = mapper.backlog();
            if (backlog == null) {
                throw new IllegalStateException("Notification backlog is unavailable");
            }
            metrics.backlog(backlog);
        } catch (RuntimeException failure) {
            metrics.failure(NotificationMetrics.Stage.BACKLOG);
        }
    }

    private void process(ClaimedEvent claim) {
        if (claim.takeover()) {
            metrics.takeover();
        }
        try {
            if (NotificationType.ignored(claim.kind())) {
                transactions.ignore(claim, owner);
                return;
            }
            var type = NotificationType.fromSource(claim.kind());
            if (type.isEmpty() || !registry.supports(type.get())) {
                // 来源解析器尚未接入：安全等待并退避，不热循环，也不当作成功空收件人。
                transactions.postpone(claim, owner, true);
                return;
            }
            long cursor = claim.recipientCursor();
            for (int i = 0; i < properties.getMaxBatchesPerEvent(); i++) {
                var batch = transactions.batch(claim, owner, type.get(), cursor);
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
            log.warn("通知派生批次失败，保留待重试");
            try {
                transactions.postpone(claim, owner, false);
            } catch (RuntimeException postponeFailure) {
                // 租约到期后由其他执行接管，已提交游标保留。
                metrics.failure(NotificationMetrics.Stage.POSTPONE);
            }
        }
    }
}
