package com.zhishiku.kb.app.storage.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度接线的边界语义：异常不外传、每次触发只调用服务一轮。
 *
 * <p>不启动 Spring 上下文、不触发真实调度：直接调用被 {@code @Scheduled}
 * 标注的方法，用 mock 服务观察调用次数与异常行为。
 */
class StorageReconciliationSchedulerTest {

    @Test
    @DisplayName("异常不外传：服务抛出的异常被调度器捕获")
    void exceptionsDoNotPropagateToSchedulingThread() {
        StorageReconciliationService service = mock(StorageReconciliationService.class);
        when(service.runCycle()).thenThrow(new IllegalStateException("db down"));
        StorageReconciliationScheduler scheduler = new StorageReconciliationScheduler(service);

        assertThatCode(scheduler::reconcile).doesNotThrowAnyException();

        verify(service).runCycle();
    }

    @Test
    @DisplayName("每轮调用一次：两次触发对应两次调用")
    void eachTriggerInvokesServiceExactlyOnce() {
        StorageReconciliationService service = mock(StorageReconciliationService.class);
        StorageReconciliationScheduler scheduler = new StorageReconciliationScheduler(service);

        scheduler.reconcile();
        scheduler.reconcile();

        verify(service, times(2)).runCycle();
    }

    @Test
    @DisplayName("调度注解：固定间隔与首轮延迟使用配置键与默认值")
    void scheduledAnnotationUsesConfiguredIntervalAndInitialDelay() throws Exception {
        Scheduled scheduled = StorageReconciliationScheduler.class
                .getMethod("reconcile")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${kb.storage-reconciliation.interval-ms:3600000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${kb.storage-reconciliation.initial-delay-ms:300000}");
    }
}
