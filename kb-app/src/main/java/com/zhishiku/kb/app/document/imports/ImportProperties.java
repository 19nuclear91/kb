package com.zhishiku.kb.app.document.imports;

import com.zhishiku.kb.app.document.imports.support.ImportInput;
import com.zhishiku.kb.app.storage.StorageProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 导入运行限额；数字以字符串接收，避免配置绑定截断浮点值。
 * @param maxItems 每批项数
 * @param maxTotalBytes 每批总字节
 * @param maxConcurrent 每实例实际在途数
 * @param maxConcurrentPerUser 每用户每实例实际在途数
 * @param attemptTimeoutSeconds 尝试期限秒数
 * @param storageTimeoutSeconds 完整存储调用秒数
 * @param recoveryIntervalMs 恢复扫描间隔毫秒
 * @param recoveryBatchSize 恢复单轮上限
 */
@ConfigurationProperties("kb.document-import")
public record ImportProperties(
        @DefaultValue("100") String maxItems,
        @DefaultValue("1073741824") String maxTotalBytes,
        @DefaultValue("8") String maxConcurrent,
        @DefaultValue("2") String maxConcurrentPerUser,
        @DefaultValue("120") String attemptTimeoutSeconds,
        @DefaultValue("60") String storageTimeoutSeconds,
        @DefaultValue("30000") String recoveryIntervalMs,
        @DefaultValue("100") String recoveryBatchSize) {

    public ImportProperties {
        value(maxItems, 1, 500);
        value(maxTotalBytes, 1, 107374182400L);
        long capacity = value(maxConcurrent, 1, 64);
        value(maxConcurrentPerUser, 1, capacity);
        long attempt = value(attemptTimeoutSeconds, 1, 120);
        value(storageTimeoutSeconds, 1, attempt);
        value(recoveryIntervalMs, 1000, 3600000);
        value(recoveryBatchSize, 1, 1000);
    }

    public int itemLimit() {
        return Integer.parseInt(maxItems);
    }

    public long totalLimit() {
        return Long.parseLong(maxTotalBytes);
    }

    public int capacity() {
        return Integer.parseInt(maxConcurrent);
    }

    public int userCapacity() {
        return Integer.parseInt(maxConcurrentPerUser);
    }

    public int attemptSeconds() {
        return Integer.parseInt(attemptTimeoutSeconds);
    }

    public int storageSeconds() {
        return Integer.parseInt(storageTimeoutSeconds);
    }

    public int recoveryLimit() {
        return Integer.parseInt(recoveryBatchSize);
    }

    public Capabilities capabilities(StorageProperties storage) {
        return new Capabilities(1, itemLimit(), totalLimit(), storage.maxUploadBytes(), ImportInput.MANIFEST_BYTES,
                Math.min(2, userCapacity()), List.of("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx"));
    }

    private static long value(String raw, long min, long max) {
        if (raw == null || !raw.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            long parsed = Long.parseLong(raw);
            if (parsed < min || parsed > max) {
                throw invalid();
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("kb.document-import 配置无效");
    }

    /**
     * 仅公开上传限制，不含服务地址或凭据。
     * @param protocolVersion 协议版本
     * @param maxItems 最大项数
     * @param maxTotalBytes 最大总字节
     * @param maxFileBytes 既有单文件上限
     * @param maxManifestBytes 清单字节上限
     * @param uploadConcurrency 建议浏览器并发
     * @param extensions 允许扩展名
     */
    @Schema(description = "导入公开能力")
    public record Capabilities(@Schema(description = "协议版本") int protocolVersion,
                               @Schema(description = "批次项数上限") int maxItems,
                               @Schema(description = "批次字节上限") long maxTotalBytes,
                               @Schema(description = "单文件字节上限") long maxFileBytes,
                               @Schema(description = "清单字节上限") int maxManifestBytes,
                               @Schema(description = "浏览器并发上限") int uploadConcurrency,
                               @Schema(description = "支持的扩展名") List<String> extensions) {
        public Capabilities {
            extensions = List.copyOf(extensions);
        }
    }
}
