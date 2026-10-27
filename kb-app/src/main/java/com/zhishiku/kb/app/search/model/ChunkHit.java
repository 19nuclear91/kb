package com.zhishiku.kb.app.search.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一条分块命中结果。
 *
 * <p>引用身份为 {@code docId / versionId / chunkId}，发布新版本不能改变旧引用。
 * 页码、章节和来源范围仅为解析器报告的核对提示，不代表原件精确坐标。
 *
 * @param score           RRF 融合得分。<b>不是 BM25 原始分，也不是相似度</b>，
 *                        仅在同一次查询内可比，不要跨查询比较或展示给用户。
 * @param chunkId         分块内部 ID
 * @param docId           文档内部 ID
 * @param versionId       命中分块所属版本 ID；旧构造调用未提供时为 {@code null}
 * @param versionNo       所属版本的展示编号，不用于定位
 * @param docTitle        文档标题
 * @param spaceId         知识空间内部 ID
 * @param securityLevel   文档密级
 * @param chunkIdx        文档内分块序号
 * @param content         分块正文
 * @param pageNo          首个来源块的页码；未知时为 {@code null}
 * @param headingPath     来源标题路径
 * @param structureType   分块结构类型
 * @param sourceLineStart 来源起始行
 * @param sourceLineEnd   来源结束行
 * @param sourceCharStart 来源起始字符位置
 * @param sourceCharEnd   来源结束字符位置
 * @param sourceLocator   解析器提供的来源定位标识
 */
@Schema(name = "ChunkHit", description = "检索命中的文档分块")
public record ChunkHit(
        @Schema(description = "分块 ID", example = "4001") Long chunkId,
        @Schema(description = "文档 ID", example = "1001") Long docId,
        @Schema(description = "分块所属版本 ID；旧数据未提供时为空", example = "2001") Long versionId,
        @Schema(description = "所属版本的展示编号，不用于定位", example = "1") Integer versionNo,
        @Schema(description = "文档标题", example = "架构说明") String docTitle,
        @Schema(description = "知识空间 ID", example = "1") Long spaceId,
        @Schema(description = "文档密级", example = "1") Short securityLevel,
        @Schema(description = "分块序号", example = "0") Integer chunkIdx,
        @Schema(description = "分块正文") String content,
        @Schema(description = "首个来源块页码，未知时为空，不表示整块页范围", example = "3") Integer pageNo,
        @Schema(description = "标题路径") String headingPath,
        @Schema(description = "结构类型", example = "PARAGRAPH") String structureType,
        @Schema(description = "解析器报告的来源起始行，仅作核对提示") Integer sourceLineStart,
        @Schema(description = "解析器报告的来源结束行，仅作核对提示") Integer sourceLineEnd,
        @Schema(description = "解析器报告的来源起始字符，不承诺原件精确坐标") Integer sourceCharStart,
        @Schema(description = "解析器报告的来源结束字符，不承诺原件精确坐标") Integer sourceCharEnd,
        @Schema(description = "来源定位标识") String sourceLocator,
        @Schema(description = "RRF 融合得分") Double score) {

    /**
     * 兼容来源元数据加入前的调用方和测试夹具。
     */
    public ChunkHit(Long chunkId, Long docId, String docTitle, Long spaceId,
                    Short securityLevel, Integer chunkIdx, String content,
                    Integer pageNo, Double score) {
        this(chunkId, docId, docTitle, spaceId, securityLevel, chunkIdx, content,
                pageNo, null, null, null, null, null, null, null, score);
    }

    /**
     * 兼容版本身份加入前的调用方，不使用当前版本猜测缺失身份。
     */
    public ChunkHit(Long chunkId, Long docId, String docTitle, Long spaceId,
                    Short securityLevel, Integer chunkIdx, String content, Integer pageNo,
                    String headingPath, String structureType, Integer sourceLineStart,
                    Integer sourceLineEnd, Integer sourceCharStart, Integer sourceCharEnd,
                    String sourceLocator, Double score) {
        this(chunkId, docId, null, null, docTitle, spaceId, securityLevel, chunkIdx, content,
                pageNo, headingPath, structureType, sourceLineStart, sourceLineEnd,
                sourceCharStart, sourceCharEnd, sourceLocator, score);
    }
}
