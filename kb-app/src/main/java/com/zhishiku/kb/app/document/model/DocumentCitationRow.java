package com.zhishiku.kb.app.document.model;

/**
 * 引用查询内部投影。父版本已经通过 view 过滤，分块通过三元组左连接。
 *
 * @param docId           文档 ID
 * @param docTitle        当前文档标题
 * @param versionId       指定版本 ID
 * @param versionNo       指定版本展示编号
 * @param matchedChunkId  匹配分块 ID；为空表示不存在匹配片段
 * @param chunkIdx        分块序号
 * @param content         完整存储正文
 * @param pageNo          首个来源块页码
 * @param headingPath     来源标题路径
 * @param structureType   来源结构类型
 * @param sourceLineStart 来源起始行提示
 * @param sourceLineEnd   来源结束行提示
 * @param sourceCharStart 来源起始字符提示
 * @param sourceCharEnd   来源结束字符提示
 * @param sourceLocator   来源标识
 */
public record DocumentCitationRow(
        Long docId, String docTitle, Long versionId, Integer versionNo,
        Long matchedChunkId, Integer chunkIdx, String content, Integer pageNo,
        String headingPath, String structureType, Integer sourceLineStart, Integer sourceLineEnd,
        Integer sourceCharStart, Integer sourceCharEnd, String sourceLocator) {
}
