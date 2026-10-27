package com.zhishiku.kb.app.notification;

import com.zhishiku.kb.app.notification.service.CommentNotificationTargets;
import com.zhishiku.kb.app.notification.service.NotificationDerivationService;
import com.zhishiku.kb.app.notification.service.NotificationDerivationTransaction;
import com.zhishiku.kb.app.notification.service.NotificationMetrics;
import com.zhishiku.kb.app.notification.service.NotificationTargetRegistry;
import com.zhishiku.kb.app.notification.service.ReviewNotificationTargets;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;

/** 真实 Spring 事务拦截器 + 快照回滚的内存事务管理器；不连接数据库。 */
final class DerivationHarness {
    final InMemoryNotificationStore store = new InMemoryNotificationStore();
    final SnapshotTransactions transactions = new SnapshotTransactions(store);
    final NotificationProperties properties = new NotificationProperties();
    final MockClock clock = new MockClock();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    final NotificationMetrics metrics = new NotificationMetrics(meters);

    NotificationDerivationTransaction boundary() {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(new NotificationDerivationTransaction(store, properties));
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return (NotificationDerivationTransaction) factory.getProxy();
    }

    /** 每次调用代表一个新进程实例，持有不同的租约身份。 */
    NotificationDerivationService instance(NotificationTargetRegistry registry) {
        properties.validate();
        return new NotificationDerivationService(boundary(), store, registry, properties, metrics);
    }

    NotificationDerivationService instance() {
        return instance(reviewRegistry());
    }

    static NotificationTargetRegistry reviewRegistry() {
        return new NotificationTargetRegistry(List.of(new ReviewNotificationTargets()));
    }

    /** 评论变更安装的真实解析器；派生只依赖其注册类型，不读取评论来源。 */
    static NotificationTargetRegistry withCommentResolver() {
        return new NotificationTargetRegistry(List.of(new ReviewNotificationTargets(), new CommentNotificationTargets()));
    }

    double counter(String name, String stage) {
        var search = meters.find(name);
        if (stage != null) {
            search = search.tag("stage", stage);
        }
        var counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    static final class SnapshotTransactions extends AbstractPlatformTransactionManager {
        private final InMemoryNotificationStore store;
        private final ThreadLocal<InMemoryNotificationStore.Snapshot> current = new ThreadLocal<>();
        final List<Integer> timeouts = new ArrayList<>();
        int commits;
        int rollbacks;

        SnapshotTransactions(InMemoryNotificationStore store) {
            this.store = store;
        }

        private static final class Holder {
            private InMemoryNotificationStore.Snapshot snapshot;
        }

        @Override
        protected Object doGetTransaction() {
            var holder = new Holder();
            holder.snapshot = current.get();
            return holder;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Holder) transaction).snapshot != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            var holder = (Holder) transaction;
            holder.snapshot = store.snapshot();
            current.set(holder.snapshot);
            timeouts.add(definition.getTimeout());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            store.restore(((Holder) status.getTransaction()).snapshot);
            rollbacks++;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
            ((Holder) transaction).snapshot = null;
        }
    }
}
