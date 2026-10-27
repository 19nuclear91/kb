package com.zhishiku.kb.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 解析与索引 Worker。
 *
 * <p>独立可部署单元，负责第三方解析调用、分块、向量化和索引发布。
 * 原件内容抽取由外部服务完成；批量导入、任务恢复与索引处理独立扩缩容，
 * 避免长任务占用 {@code kb-app} 的在线请求资源。
 *
 * <p><b>Flyway 不在此模块启用</b>：库表迁移由 {@code kb-app} 单点负责。
 * 多个部署单元同时跑迁移会互相抢锁，且版本推进责任不清。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
@MapperScan("com.zhishiku.kb.worker.**.mapper")
public class KbWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbWorkerApplication.class, args);
    }
}
