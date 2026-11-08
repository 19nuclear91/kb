package com.zhishiku.kb.app.document.imports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

/** 导入固定意图与公开投影；读取原清单与读取受理历史分别授权。 */
public final class ImportModels {
    private ImportModels() {
    }

    public enum ItemState {
        PENDING, PROCESSING, ACCEPTED, FAILED
    }

    public enum AttemptState {
        ACTIVE, ACCEPTED, INVALIDATED
    }

    public enum BatchState {
        OPEN, COMPLETED, PARTIAL_FAILED, FAILED
    }

    public enum FailureCode {
        INVALID_FILE, PAYLOAD_TOO_LARGE, STORAGE_UNAVAILABLE, EXPIRED, ACCESS_DENIED
    }

    /**
     * 固定文件描述，摘要只用于匹配本项。
     * @param itemKey 客户端项键
     * @param fileName 安全文件名
     * @param title 规范化标题
     * @param sizeBytes 原件字节数
     * @param sha256 小写十六进制摘要
     */
    @Schema(description = "固定导入文件描述")
    public record FileIntent(
            @Schema(description = "批次内唯一项键") String itemKey,
            @Schema(description = "原文件名") String fileName,
            @Schema(description = "文档标题") String title,
            @Schema(description = "文件字节数") long sizeBytes,
            @Schema(description = "本项内容 SHA-256") String sha256) {
    }

    /**
     * 规范化后不可变的完整清单。
     * @param spaceId 目标空间
     * @param securityLevel 字典内密级
     * @param items 有序文件项
     * @param totalBytes 总字节数
     * @param digest 完整规范化意图摘要
     */
    public record Manifest(long spaceId, short securityLevel, List<FileIntent> items, long totalBytes,
                           String digest) {
        public Manifest {
            items = List.copyOf(items);
        }
    }

    /**
     * 创建批次的固定回执。
     * @param importId 批次身份
     * @param replayed 是否匹配重放
     */
    @Schema(description = "导入批次创建回执")
    public record Created(@Schema(description = "导入批次 ID") long importId,
                          @Schema(description = "匹配重放") boolean replayed) {
    }

    /**
     * 查询创建结果，不表达原在途请求已经取消。
     * @param found 当前是否可读取已提交批次
     * @param importId 已提交批次身份，未查得时为空
     */
    @Schema(description = "导入创建结果核对")
    public record Resolution(@Schema(description = "已查得持久批次") boolean found,
                             @Schema(description = "已提交批次 ID") Long importId) {
    }

    /**
     * 原子受理的固定结果，不表达当前文档访问资格。
     * @param documentId 文档身份
     * @param versionId 首个版本身份
     * @param parseTaskId 解析任务身份
     */
    @Schema(description = "文件已受理的固定三元组")
    public record Receipt(@Schema(description = "文档 ID") long documentId,
                          @Schema(description = "版本 ID") long versionId,
                          @Schema(description = "解析任务 ID") long parseTaskId) {
    }

    /**
     * 同一次读取的互斥项计数。
     * @param pending 待上传数
     * @param processing 处理数
     * @param accepted 已受理数
     * @param failed 失败数
     */
    @Schema(description = "导入受理阶段计数")
    public record Counts(@Schema(description = "待上传") int pending,
                         @Schema(description = "处理中") int processing,
                         @Schema(description = "已受理") int accepted,
                         @Schema(description = "失败") int failed) {
        public Counts {
            if (pending < 0 || processing < 0 || accepted < 0 || failed < 0) {
                throw new IllegalArgumentException("Invalid import counts");
            }
            Math.addExact(Math.addExact(pending, processing), Math.addExact(accepted, failed));
        }

        public BatchState state() {
            if (pending > 0 || processing > 0) {
                return BatchState.OPEN;
            }
            if (failed == 0 && accepted > 0) {
                return BatchState.COMPLETED;
            }
            return accepted > 0 ? BatchState.PARTIAL_FAILED : BatchState.FAILED;
        }
    }

    /**
     * 本人任务摘要；原空间失去上传资格时不返回清单目标。
     * @param importId 批次身份
     * @param createdAt 创建时间
     * @param state 受理阶段状态
     * @param counts 同次项计数
     * @param canContinue 当前能否继续上传
     * @param spaceId 获授权的原目标空间
     * @param securityLevel 获授权的原密级
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "本人导入任务")
    public record BatchView(@Schema(description = "批次 ID") long importId,
                            @Schema(description = "创建时间") OffsetDateTime createdAt,
                            @Schema(description = "受理状态") BatchState state,
                            @Schema(description = "受理计数") Counts counts,
                            @Schema(description = "当前上传资格") boolean canContinue,
                            @Schema(description = "原目标空间") Long spaceId,
                            @Schema(description = "原密级") Short securityLevel) {
    }

    /**
     * 本人项历史；文件描述只按当前上传资格提供。
     * @param itemId 项身份
     * @param importId 批次身份
     * @param ordinal 固定顺序，从一开始
     * @param revision 状态代次
     * @param state 当前受理状态
     * @param failureCode 固定失败代码
     * @param canUpload 当前能否主动上传
     * @param file 获授权的原文件描述
     * @param receipt 已受理固定结果
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "本人导入文件项")
    public record ItemView(@Schema(description = "项 ID") long itemId,
                           @Schema(description = "批次 ID") long importId,
                           @Schema(description = "固定顺序") int ordinal,
                           @Schema(description = "状态代次") long revision,
                           @Schema(description = "受理状态") ItemState state,
                           @Schema(description = "固定失败代码") FailureCode failureCode,
                           @Schema(description = "允许主动上传") boolean canUpload,
                           @Schema(description = "原文件描述") FileIntent file,
                           @Schema(description = "受理回执") Receipt receipt) {
    }

    /**
     * 有界游标分页。
     * @param items 本页记录
     * @param hasMore 是否还有记录
     * @param nextCursor 下一页游标
     * @param <T> 记录类型
     */
    @Schema(description = "导入游标分页")
    public record Page<T>(@Schema(description = "当前页") List<T> items,
                          @Schema(description = "是否还有记录") boolean hasMore,
                          @Schema(description = "下一页游标") String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
