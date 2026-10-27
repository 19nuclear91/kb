package com.zhishiku.kb.app.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储对账配置。默认只报告不删除；删除需要显式开启。
 *
 * <p>缺失的上限值回填设计默认值；显式配置 0 或负数表示该项关闭：删除上限为 0
 * 时即使开启删除也不会删除对象，缺失抽样上限为 0 时不执行缺失检测。时间类
 * 字段非正数回填默认值，避免误配出过于激进的轮次或保护窗口。
 *
 * @param intervalMs              两轮对账的间隔，单位毫秒；非正数回填 3600000
 * @param initialDelayMs          启动后首轮对账的延迟，单位毫秒；非正数回填 300000
 * @param orphanGraceSeconds      孤儿保护窗口，单位秒；最后修改时间晚于该窗口的对象不视为可删除孤儿
 * @param prefix                  扫描前缀；为空时使用 {@link ObjectKeys#DOCUMENTS_PREFIX}
 * @param deleteEnabled           是否启用删除；默认 false，仅报告孤儿
 * @param maxDeletesPerCycle      单轮删除上限；null 回填 500，显式非正数表示本轮不删除
 * @param missingCheckEnabled     是否启用存活引用的缺失检测；默认 true
 * @param missingCheckMaxPerCycle 单轮缺失抽样上限；null 回填 100，显式非正数表示不检测
 */
@ConfigurationProperties(prefix = "kb.storage-reconciliation")
public record StorageReconciliationProperties(
        long intervalMs,
        long initialDelayMs,
        long orphanGraceSeconds,
        String prefix,
        boolean deleteEnabled,
        Integer maxDeletesPerCycle,
        Boolean missingCheckEnabled,
        Integer missingCheckMaxPerCycle) {

    public StorageReconciliationProperties {
        if (intervalMs <= 0) {
            intervalMs = 3_600_000L;
        }
        if (initialDelayMs <= 0) {
            initialDelayMs = 300_000L;
        }
        if (orphanGraceSeconds <= 0) {
            orphanGraceSeconds = 3_600L;
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = ObjectKeys.DOCUMENTS_PREFIX;
        }
        if (maxDeletesPerCycle == null) {
            maxDeletesPerCycle = 500;
        } else if (maxDeletesPerCycle < 0) {
            maxDeletesPerCycle = 0;
        }
        if (missingCheckEnabled == null) {
            missingCheckEnabled = true;
        }
        if (missingCheckMaxPerCycle == null) {
            missingCheckMaxPerCycle = 100;
        } else if (missingCheckMaxPerCycle < 0) {
            missingCheckMaxPerCycle = 0;
        }
    }
}
