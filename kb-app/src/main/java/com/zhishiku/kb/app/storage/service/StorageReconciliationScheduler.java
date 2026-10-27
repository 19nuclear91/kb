package com.zhishiku.kb.app.storage.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对象存储对账的调度接线：按固定间隔触发一轮对账。
 *
 * <p>间隔与首轮延迟分别来自 {@code kb.storage-reconciliation.interval-ms} 与
 * {@code kb.storage-reconciliation.initial-delay-ms}。轮次内部的失败已由
 * {@link StorageReconciliationService#runCycle()} 收敛为报告；这里再兜底捕获
 * 所有异常并记 ERROR，确保未预期异常不会传播到调度线程、影响其他定时任务。
 */
@Component
public class StorageReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationScheduler.class);

    private final StorageReconciliationService reconciliationService;

    public StorageReconciliationScheduler(StorageReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * 触发一轮对账；异常一律捕获记录，不向调度线程传播。
     */
    @Scheduled(fixedDelayString = "${kb.storage-reconciliation.interval-ms:3600000}",
            initialDelayString = "${kb.storage-reconciliation.initial-delay-ms:300000}")
    public void reconcile() {
        try {
            reconciliationService.runCycle();
        } catch (Exception e) {
            log.error("对象存储对账轮次出现未预期异常，已捕获，不向调度线程传播", e);
        }
    }
}
