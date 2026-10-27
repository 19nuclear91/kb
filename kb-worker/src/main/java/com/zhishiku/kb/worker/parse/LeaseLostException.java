package com.zhishiku.kb.worker.parse;

/**
 * 任务租约已被回收，当前实例必须放弃处理且不能写失败状态。
 */
public class LeaseLostException extends RuntimeException {
    public LeaseLostException() {
        super("解析任务租约已失守");
    }
}
