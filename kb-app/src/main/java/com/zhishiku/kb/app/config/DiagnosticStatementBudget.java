package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.auth.service.diagnostics.DiagnosticBudget;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/** 每条 SQL 使用本次诊断剩余预算，包括共用身份与资源 Mapper。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class DiagnosticStatementBudget implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var budget = DiagnosticBudget.current();
        if (budget == null) {
            return invocation.proceed();
        }
        var connection = (Connection) invocation.getArgs()[0];
        try (var settings = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
            String remaining = Long.toString(budget.remainingMillis());
            settings.setString(1, remaining);
            settings.setString(2, remaining);
            settings.setQueryTimeout((int) Math.max(1, (budget.remainingMillis() + 999) / 1000));
            settings.execute();
        }
        budget.remainingMillis();
        Object result = invocation.proceed();
        if (result instanceof Statement statement) {
            statement.setQueryTimeout((int) Math.max(1, (budget.remainingMillis() + 999) / 1000));
        }
        return result;
    }
}
