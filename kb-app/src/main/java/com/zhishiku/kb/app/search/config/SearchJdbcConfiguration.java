package com.zhishiku.kb.app.search.config;

import com.zhishiku.kb.app.search.execution.SearchDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class SearchJdbcConfiguration {
    @Bean
    public static BeanPostProcessor searchDataSourceDecorator() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource source && !(bean instanceof SearchDataSource)) {
                    return new SearchDataSource(source);
                }
                return bean;
            }
        };
    }
}
