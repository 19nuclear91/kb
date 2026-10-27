package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 当前可查看文档的操作资格，不承诺文件存在或版本可重试。
 *
 * @param documentId 文档标识
 * @param canDownload 独立下载资格
 * @param canUploadVersion 独立编辑资格
 * @param canRetry 独立编辑资格，任务状态另行判定
 */
@Schema(description = "当前文档操作资格")
public record DocumentCapabilities(long documentId, boolean canDownload, boolean canUploadVersion, boolean canRetry) {
}
