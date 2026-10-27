package com.zhishiku.kb.app.storage;

/**
 * 一轮对账的结果计数。
 *
 * <p>计数独立于内存中保留的候选规模：扫描数与孤儿数按实际发现累加，
 * 删除候选与日志键的保留量另有各自上限。
 *
 * @param scanned            本轮列举并检查的对象数
 * @param orphans            识别出的孤儿对象总数
 * @param deleted            成功删除的对象数
 * @param deleteFailed       删除失败的对象数（由下一轮自然重试）
 * @param missing            存活引用在桶中缺失的对象数
 * @param missingCheckFailed 缺失检测中检查失败（非 NotFound 异常）的对象数
 */
public record StorageReconciliationReport(long scanned, long orphans, long deleted, long deleteFailed,
                                          long missing, long missingCheckFailed) {

    /**
     * 未执行任何检查的轮次结果：未获单飞锁或轮次开始前失败。
     */
    public static StorageReconciliationReport empty() {
        return new StorageReconciliationReport(0, 0, 0, 0, 0, 0);
    }
}
