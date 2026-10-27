package com.zhishiku.kb.app.storage;

import java.io.InputStream;

/**
 * 文档原件存储抽象。业务层不依赖 MinIO 或具体 S3 厂商。
 */
public interface ObjectStorage {

    void put(String objectKey, InputStream content, long contentLength,
             String contentType, String sha256);

    void delete(String objectKey);

    /**
     * 返回对象流。调用方负责关闭流。必须在业务权限校验之后调用。
     */
    InputStream get(String objectKey);

    /**
     * 分页列举前缀下的对象元数据。
     *
     * <p>只读取键与最后修改时间，不读取对象内容。{@code continuationToken} 为
     * null 时从头开始；返回的 {@code nextCursor} 为 null 表示已到末页。读取
     * 路径不创建桶，桶不存在等错误直接抛出。
     *
     * @param prefix            对象键前缀
     * @param continuationToken 上一页返回的游标；首页传 null
     * @param maxKeys           本页最多返回的对象数
     * @return 本页对象与下一页游标
     */
    ListObjectsPage list(String prefix, String continuationToken, int maxKeys);

    /**
     * 判断对象是否存在。
     *
     * <p>仅“对象不存在”（S3 的 NotFound）返回 false；桶不存在、权限不足、
     * 网络错误等其他异常一律抛出，由调用方与本轮失败区分。
     *
     * @param objectKey 对象键
     * @return 对象存在时为 true
     */
    boolean objectExists(String objectKey);
}
