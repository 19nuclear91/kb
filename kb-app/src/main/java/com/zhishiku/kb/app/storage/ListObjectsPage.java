package com.zhishiku.kb.app.storage;

import java.util.List;

/**
 * 一次分页列举的结果页。
 *
 * @param objects    本页对象元数据，顺序与存储服务返回一致
 * @param nextCursor 下一页游标；null 表示已到末页
 */
public record ListObjectsPage(List<StoredObject> objects, String nextCursor) {
}
