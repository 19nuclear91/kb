package com.zhishiku.kb.app.support;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 MyBatis 参数处理器的取值规则还原每个占位符实际绑定的值；只构建 BoundSql，不执行 SQL 或连接数据库。
 */
public final class BoundValues {
    private BoundValues() {
    }

    /** 每个占位符的属性名与值，顺序与 SQL 中的占位符一致。 */
    public static List<Object> values(Configuration configuration, String statementId, Object parameter) {
        BoundSql bound = configuration.getMappedStatement(statementId).getBoundSql(parameter);
        var meta = parameter == null ? null : configuration.newMetaObject(parameter);
        List<Object> values = new ArrayList<>();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            String property = mapping.getProperty();
            if (bound.hasAdditionalParameter(property)) {
                values.add(bound.getAdditionalParameter(property));
            } else if (parameter != null && configuration.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass())) {
                values.add(parameter);
            } else {
                values.add(meta == null ? null : meta.getValue(property));
            }
        }
        return values;
    }

    public static List<String> properties(Configuration configuration, String statementId, Object parameter) {
        return configuration.getMappedStatement(statementId).getBoundSql(parameter).getParameterMappings().stream()
                .map(ParameterMapping::getProperty).toList();
    }
}
