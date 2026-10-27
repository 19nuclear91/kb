package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 密级管理最小投影，不包含正文及文件地址。
 *
 * @param documentId      文档 ID
 * @param securityLevel   文档密级
 * @param securityVersion 文档密级的独立乐观锁版本
 */
@Schema(description = "文档当前密级及其独立版本。")
public record DocumentSecurityLevelState(
        @Schema(description = "文档 ID") long documentId,
        @Schema(description = "文档密级") short securityLevel,
        @Schema(description = "文档密级的独立乐观锁版本") long securityVersion) {
}
