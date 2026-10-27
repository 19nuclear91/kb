package com.zhishiku.kb.worker.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 访问 S3 兼容对象存储的配置。
 *
 * @param endpoint        自定义服务地址；为空时使用云厂商默认地址
 * @param region          存储区域
 * @param accessKey       访问密钥 ID
 * @param secretKey       访问密钥；不得写入日志
 * @param bucket          对象存储桶名称
 * @param pathStyleAccess 是否使用 path-style 地址
 */
@ConfigurationProperties(prefix = "kb.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        boolean pathStyleAccess) {

    public StorageProperties {
        region = region == null || region.isBlank() ? "us-east-1" : region;
        bucket = bucket == null || bucket.isBlank() ? "kb-documents" : bucket;
    }
}
