package com.zhishiku.kb.app.storage;

import java.time.Instant;

/**
 * 桶内对象的最小元数据。对账只消费键与最后修改时间，不读取对象内容。
 *
 * @param key          对象键
 * @param lastModified 对象最后修改时间；孤儿保护窗口据此判断对象是否足够旧
 */
public record StoredObject(String key, Instant lastModified) {
}
