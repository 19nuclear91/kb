package com.zhishiku.kb.app.search.execution;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;

/** MyBatis 完成自身超时设置后再次收紧，JDBC 包装负责执行与结果读取期间的取消。 */
@Component
@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SearchStatementInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        var context = SearchContext.current();
        if (context == null) {
            return invocation.proceed();
        }
        context.check();
        Statement statement = (Statement) invocation.proceed();
        try {
            context.check();
            int configured = statement.getQueryTimeout();
            int remaining = context.window().queryTimeoutSeconds();
            statement.setQueryTimeout(configured <= 0 ? remaining : Math.min(configured, remaining));
            return statement;
        } catch (Throwable failure) {
            statement.close();
            throw failure;
        }
    }
}
