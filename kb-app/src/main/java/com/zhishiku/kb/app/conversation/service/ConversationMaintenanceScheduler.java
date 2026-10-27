package com.zhishiku.kb.app.conversation.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 有界后台任务只关闭中断轮次及清理费用，不继续生成。
 */
@Service
@EnableScheduling
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationMaintenanceScheduler {
    private final ConversationMaintenanceService maintenance;
    private final TurnExecutionService execution;
    private final SettlementDeliveryClient delivery;

    public ConversationMaintenanceScheduler(ConversationMaintenanceService maintenance,
                                            TurnExecutionService execution, SettlementDeliveryClient delivery) {
        this.maintenance = maintenance;
        this.execution = execution;
        this.delivery = delivery;
    }

    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void tick() {
        for (long id : maintenance.expired()) {
            try {
                execution.recover(id);
            } catch (RuntimeException error) { /* 下一轮持久恢复；不记录正文异常。 */ }
        }
        for (var intent : maintenance.claim()) {
            boolean successful = false;
            if (maintenance.retryable(intent)) {
                try {
                    delivery.deliver(intent);
                    successful = true;
                } catch (RuntimeException error) { /* 相同身份退避重试。 */ }
            }
            maintenance.acknowledge(intent, successful);
        }
    }
}

