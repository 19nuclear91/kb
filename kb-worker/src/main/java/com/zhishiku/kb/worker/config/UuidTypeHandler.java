package com.zhishiku.kb.worker.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.springframework.stereotype.Component;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

// 两个部署单元独立注册，避免共享契约模块依赖 MyBatis 或 Spring。
@Component
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType jdbcType)
            throws SQLException {
        statement.setObject(index, value, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet results, String column) throws SQLException {
        return results.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(ResultSet results, int column) throws SQLException {
        return results.getObject(column, UUID.class);
    }

    @Override
    public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
        return statement.getObject(column, UUID.class);
    }
}
