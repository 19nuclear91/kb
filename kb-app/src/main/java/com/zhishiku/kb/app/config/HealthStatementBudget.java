package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.service.impl.health.HealthBudget;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.Statement;

/** 诊断调用内所有 Mapper（含身份与空间）共享总期限，每条语句至多三秒。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class HealthStatementBudget implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var budget = HealthBudget.current();
        if (budget == null) {
            return invocation.proceed();
        }
        var connection = (Connection) invocation.getArgs()[0];
        try (var settings = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
            long remaining = Math.min(3000, budget.remainingMillis());
            settings.setString(1, Long.toString(remaining));
            settings.setString(2, Long.toString(remaining));
            settings.setQueryTimeout((int) Math.max(1, (remaining + 999) / 1000));
            settings.execute();
        }
        budget.remainingMillis();
        Object result = invocation.proceed();
        if (result instanceof Statement statement) {
            try {
                statement.setQueryTimeout((int) Math.max(1, (Math.min(3000, budget.remainingMillis()) + 999) / 1000));
            } catch (Exception error) {
                // prepare 尚未返回，MyBatis 无法接管此语句；失败时在这里释放。
                try {
                    statement.close();
                } catch (Exception ignored) {
                    // 保留原始预算/设置失败，由雷达边界投影为安全错误。
                }
                throw error;
            }
        }
        return result;
    }
}
