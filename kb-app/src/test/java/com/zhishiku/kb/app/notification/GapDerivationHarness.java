package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.service.GapNotificationDerivationService;
import com.zhishiku.kb.app.notification.service.GapNotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 独立来源内存仓储 + 实际 Spring 事务代理；不连接数据库，不启动调度器或应用。 */
final class GapDerivationHarness {
    final InMemoryNotificationStore store = new InMemoryNotificationStore(GapNotificationDerivationTransaction.SOURCE_KINDS);
    final DerivationHarness.SnapshotTransactions transactions = new DerivationHarness.SnapshotTransactions(store);
    final NotificationProperties properties = new NotificationProperties();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final NotificationMetrics metrics = new NotificationMetrics(meters);

    GapNotificationDerivationTransaction boundary() {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new GapNotificationDerivationTransaction(store, properties));
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return (GapNotificationDerivationTransaction) factory.getProxy();
    }

    GapNotificationDerivationService instance() {
        properties.validate();
        return new GapNotificationDerivationService(boundary(), properties, metrics);
    }
}
