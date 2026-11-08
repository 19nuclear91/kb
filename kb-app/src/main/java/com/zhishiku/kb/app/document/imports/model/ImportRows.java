package com.zhishiku.kb.app.document.imports.model;

import com.zhishiku.kb.app.document.imports.model.ImportModels.AttemptState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FailureCode;
import com.zhishiku.kb.app.document.imports.model.ImportModels.FileIntent;
import com.zhishiku.kb.app.document.imports.model.ImportModels.ItemState;
import com.zhishiku.kb.app.document.imports.model.ImportModels.Receipt;

import java.time.OffsetDateTime;

/** 持久记录不得直接作为 HTTP 投影返回。 */
public final class ImportRows {
    private ImportRows() {
    }

    /**
     * 持久批次。
     * @param id 批次身份
     * @param ownerUserId 创建者
     * @param createRequestId 创建请求标识
     * @param manifestDigest 固定意图摘要
     * @param spaceId 目标空间
     * @param securityLevel 原密级
     * @param itemCount 固定项数
     * @param totalBytes 声明总字节
     * @param createdAt 创建时间
     */
    public record Batch(long id, long ownerUserId, String createRequestId, String manifestDigest, long spaceId,
                        short securityLevel, int itemCount, long totalBytes, OffsetDateTime createdAt) {
    }

    /**
     * 持久文件项。
     * @param id 项身份
     * @param importId 所属批次
     * @param itemKey 客户端项键
     * @param ordinal 固定顺序
     * @param fileName 文件名
     * @param title 固定标题
     * @param sizeBytes 字节数
     * @param sha256 内容摘要
     * @param state 受理状态
     * @param revision 状态代次
     * @param attemptId 当前尝试
     * @param documentId 已受理文档
     * @param versionId 已受理版本
     * @param parseTaskId 已受理解析任务
     * @param failureCode 固定失败码
     */
    public record Item(long id, long importId, String itemKey, int ordinal, String fileName, String title,
                       long sizeBytes, String sha256, ItemState state, long revision, Long attemptId,
                       Long documentId, Long versionId, Long parseTaskId, FailureCode failureCode) {
        public FileIntent file() {
            return new FileIntent(itemKey, fileName, title, sizeBytes, sha256);
        }

        public Receipt receipt() {
            if (state != ItemState.ACCEPTED) {
                return null;
            }
            return new Receipt(documentId, versionId, parseTaskId);
        }
    }

    /**
     * 独立执行身份与永不复用的对象键。
     * @param id 尝试身份
     * @param itemId 文件项
     * @param requestId 尝试请求标识
     * @param bindingDigest 请求绑定摘要
     * @param generation 执行代次
     * @param objectKey 本次独有对象键
     * @param state 执行状态
     * @param authEpoch 领取时认证代次
     * @param startedAt 数据库开始时间
     * @param leaseUntil 数据库截止
     * @param endedAt 结束时间
     */
    public record Attempt(long id, long itemId, String requestId, String bindingDigest, long generation,
                          String objectKey, AttemptState state, long authEpoch, OffsetDateTime startedAt,
                          OffsetDateTime leaseUntil, OffsetDateTime endedAt) {
    }
}
