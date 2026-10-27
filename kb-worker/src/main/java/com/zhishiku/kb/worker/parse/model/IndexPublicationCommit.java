package com.zhishiku.kb.worker.parse.model;

/**
 * 已生成结果的不可变索引身份与解析元数据。
 * @param versionId 精确版本
 * @param parserName 解析器标识
 * @param parserVersion 解析器版本
 * @param truncated 解析截断标记
 * @param issueCount 解析问题数量
 * @param profileId 生成时固定 profile
 * @param fingerprint 生成时固定语义指纹
 * @param epoch 执行代次
 * @param chunkCount 本次完整分块数
 */
public record IndexPublicationCommit(long versionId, String parserName, String parserVersion, boolean truncated,
                                      int issueCount, String profileId, String fingerprint, long epoch, int chunkCount) {
}
