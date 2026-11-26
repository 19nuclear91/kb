package com.zhishiku.kb.app.mapper.answercard;

import com.zhishiku.kb.app.KbAppApplication;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.mapper.ClassPathMapperScanner;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 只扫描Bean定义，不创建容器或连接SQLSession，验证技术分层下的Mapper可被正式入口发现。 */
class AnswerCardMapperDiscoveryTest {
    @Test
    void productionMapperScanFindsNestedTechnicalPackages() {
        var registry = new DefaultListableBeanFactory();
        var scanner = new ClassPathMapperScanner(registry);
        scanner.registerFilters();
        scanner.scan(KbAppApplication.class.getAnnotation(MapperScan.class).value());
        for (Class<?> type : new Class<?>[]{AnswerCardMapper.class, AnswerCardSourceMapper.class,
                AnswerCardQueryMapper.class, AnswerCardCommandMapper.class}) {
            String name = Character.toLowerCase(type.getSimpleName().charAt(0)) + type.getSimpleName().substring(1);
            assertThat(registry.getBeanDefinition(name).getConstructorArgumentValues().getGenericArgumentValues())
                    .anySatisfy(value -> assertThat(value.getValue()).isEqualTo(type.getName()));
        }
    }
}
