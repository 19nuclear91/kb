package com.zhishiku.kb.app;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 业务主应用（模块化单体）。
 *
 * <p>单进程部署，内部按业务模块分包：
 * {@code auth} / {@code document} / {@code search} / {@code file} / {@code stat} / {@code base}。
 *
 * <p>模块边界即《架构决策记录.md》v1 图中的微服务边界，一个不少。
 * 依赖方向由 {@code ModuleBoundaryTest} 在测试阶段校验——
 * 注意 ArchUnit 跑在测试期，不提供编译期隔离，CI 必须执行测试才有约束力。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.zhishiku.kb.app.**.mapper")
public class KbAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbAppApplication.class, args);
    }
}
