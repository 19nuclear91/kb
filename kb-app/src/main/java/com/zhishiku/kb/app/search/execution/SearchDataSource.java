package com.zhishiku.kb.app.search.execution;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** 仅为当前搜索包装连接及语句，迟到连接仍由取得它的工作线程关闭。 */
public final class SearchDataSource extends DelegatingDataSource {
    public SearchDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return acquire(() -> obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return acquire(() -> obtainTargetDataSource().getConnection(username, password));
    }

    private Connection acquire(ConnectionFactory factory) throws SQLException {
        var context = SearchContext.current();
        if (context == null) {
            return factory.get();
        }
        context.check();
        Connection connection = factory.get();
        try {
            context.check();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("createStatement") || name.equals("prepareStatement") || name.equals("prepareCall")) {
                            context.check();
                            Statement statement = (Statement) invoke(connection, method, arguments);
                            try {
                                context.check();
                                return statement(statement, context, (Connection) proxy);
                            } catch (RuntimeException | SQLException failure) {
                                statement.close();
                                throw failure;
                            }
                        }
                        if (name.equals("commit")) {
                            context.check();
                        }
                        return invoke(connection, method, arguments);
                    });
        } catch (RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static Statement statement(Statement target, SearchContext context, Connection connection) throws SQLException {
        target.setQueryTimeout(context.window().queryTimeoutSeconds());
        var registration = context.onCancel(() -> {
            try {
                target.cancel();
            } catch (SQLException ignored) {
                // 取消失败不改变实际工作生命周期，驱动退出后才能归还容量。
            }
        });
        Class<?> type = target instanceof CallableStatement ? CallableStatement.class
                : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            String name = method.getName();
            if (name.equals("close")) {
                try {
                    return invoke(target, method, arguments);
                } finally {
                    registration.close();
                }
            }
            if (name.equals("getConnection")) {
                return connection;
            }
            if (name.equals("setQueryTimeout")) {
                context.check();
                int requested = (int) arguments[0];
                int remaining = context.window().queryTimeoutSeconds();
                target.setQueryTimeout(requested <= 0 ? remaining : Math.min(requested, remaining));
                return null;
            }
            boolean executing = name.startsWith("execute") || name.equals("getMoreResults");
            if (executing) {
                context.check();
                target.setQueryTimeout(context.window().queryTimeoutSeconds());
            }
            Object result = invoke(target, method, arguments);
            if (executing) {
                context.check();
            }
            if (result instanceof ResultSet rows) {
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                        (rowProxy, rowMethod, rowArguments) -> {
                            if (rowMethod.getName().equals("next")) {
                                context.check();
                                Object next = invoke(rows, rowMethod, rowArguments);
                                context.check();
                                return next;
                            }
                            return invoke(rows, rowMethod, rowArguments);
                        });
            }
            return result;
        });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface ConnectionFactory {
        Connection get() throws SQLException;
    }
}
