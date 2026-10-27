package com.zhishiku.kb.app.storage;

/**
 * 对象键约定。上传与对账共用同一前缀常量，避免字面量散落导致扫描范围失配。
 */
public final class ObjectKeys {

    /**
     * 文档原件在桶内的固定前缀；对象存储对账只扫描该前缀下的对象。
     */
    public static final String DOCUMENTS_PREFIX = "documents/";

    private ObjectKeys() {
    }
}