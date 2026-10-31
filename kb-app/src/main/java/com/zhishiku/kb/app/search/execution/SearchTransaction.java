package com.zhishiku.kb.app.search.execution;

import com.zhishiku.kb.app.search.model.SearchFailure;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.function.Supplier;

/** 每次召回独立构造动态短事务；局部参数、共享锁及 SQL 使用同一个事务连接。 */
@Component
public class SearchTransaction {
    private final PlatformTransactionManager manager;
    private final DataSource dataSource;

    public SearchTransaction(PlatformTransactionManager manager, DataSource dataSource) {
        this.manager = manager;
        this.dataSource = dataSource;
    }

    public <T> T execute(Supplier<T> action) {
        var context = SearchContext.current();
        if (context == null) {
            throw new IllegalStateException("召回缺少搜索预算");
        }
        context.check();
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(context.window().queryTimeoutSeconds());
        return transaction.execute(status -> {
            context.check();
            var connection = DataSourceUtils.getConnection(dataSource);
            try (var settings = connection.prepareStatement(
                    "SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
                long nanos = context.window().remainingNanos();
                String millis = Long.toString(Math.max(1, nanos / 1_000_000L));
                settings.setString(1, millis);
                settings.setString(2, millis);
                settings.setQueryTimeout(context.window().queryTimeoutSeconds());
                context.check();
                settings.execute();
                context.check();
                T result = action.get();
                context.check();
                return result;
            } catch (SQLException unavailable) {
                throw new SearchFailure(SearchFailure.Kind.DEPENDENCY);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }
}
