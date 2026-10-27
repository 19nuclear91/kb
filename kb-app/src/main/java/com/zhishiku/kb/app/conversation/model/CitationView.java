package com.zhishiku.kb.app.conversation.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 不包含材料正文的引用
 *
 * @param documentId       文档 ID
 * @param versionId        固定版本 ID
 * @param chunkId          分块 ID
 * @param title            来源标题
 * @param versionNo        版本编号
 * @param pageNo           来源页码
 * @param headingPath      标题路径
 * @param structureType    结构类型
 * @param sourceLocator    定位标识
 * @param sourceLineStart  来源起始行
 * @param sourceLineEnd    来源结束行
 * @param sourceCharStart  来源起始字符
 * @param sourceCharEnd    来源结束字符
 * @param contextTruncated 本轮材料是否裁剪
 */
@Schema(description = "不包含材料正文的引用")
public record CitationView(
        @Schema(description = "文档 ID") long documentId,
        @Schema(description = "固定版本 ID") long versionId,
        @Schema(description = "分块 ID") long chunkId,
        @Schema(description = "来源标题") String title,
        @Schema(description = "版本编号") Integer versionNo,
        @Schema(description = "来源页码") Integer pageNo,
        @Schema(description = "标题路径") String headingPath,
        @Schema(description = "结构类型") String structureType,
        @Schema(description = "定位标识") String sourceLocator,
        @Schema(description = "来源起始行") Integer sourceLineStart,
        @Schema(description = "来源结束行") Integer sourceLineEnd,
        @Schema(description = "来源起始字符") Integer sourceCharStart,
        @Schema(description = "来源结束字符") Integer sourceCharEnd,
        @Schema(description = "本轮材料是否裁剪") boolean contextTruncated) {
}

