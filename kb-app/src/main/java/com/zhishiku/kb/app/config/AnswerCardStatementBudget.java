package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.service.impl.answercard.AnswerCardSearchBudget;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;
import java.sql.Connection;
import java.sql.Statement;

/** PostgreSQL毫秒级剩余预算；JDBC整秒超时只是额外兜底。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class AnswerCardStatementBudget implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var budget = AnswerCardSearchBudget.current();
        if (budget == null) {
            return invocation.proceed();
        }
        Connection connection = (Connection) invocation.getArgs()[0];
        try (var setting = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true), set_config('lock_timeout', ?, true)")) {
            String millis = Integer.toString(budget.statementMillis());
            setting.setString(1, millis);
            setting.setString(2, millis);
            setting.setQueryTimeout((budget.statementMillis() + 999) / 1000);
            setting.execute();
        }
        budget.remainingNanos();
        Object result = invocation.proceed();
        if (result instanceof Statement statement) {
            statement.setQueryTimeout((budget.statementMillis() + 999) / 1000);
        }
        return result;
    }
}
