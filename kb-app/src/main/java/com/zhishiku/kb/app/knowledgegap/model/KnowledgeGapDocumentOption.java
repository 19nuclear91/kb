package com.zhishiku.kb.app.knowledgegap.model;

/** 只投影当前授权的标题与固定资料身份，不暴露提问者的权限范围。 */
public record KnowledgeGapDocumentOption(long documentId, long versionId, String title) {
}
