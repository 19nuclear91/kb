package com.zhishiku.kb.worker.parse;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Worker 文档摄取和分块限制。
 *
 * @param maxFileBytes      单个原始文件大小上限，单位为字节
 * @param maxExtractedChars 单个文档抽取字符数上限
 * @param chunkSize         分块目标字符数
 * @param chunkOverlap      相邻分块重叠字符数
 */
@ConfigurationProperties(prefix = "kb.worker.ingestion")
public record IngestionProperties(
        long maxFileBytes,
        int maxExtractedChars,
        int chunkSize,
        int chunkOverlap) {

    public IngestionProperties {
        if (maxFileBytes <= 0) maxFileBytes = 50L * 1024 * 1024;
        if (maxExtractedChars <= 0) maxExtractedChars = 5_000_000;
        if (chunkSize < 100) chunkSize = 1200;
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) chunkOverlap = 200;
    }
}
