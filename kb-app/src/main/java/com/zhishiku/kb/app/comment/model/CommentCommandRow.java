package com.zhishiku.kb.app.comment.model;

import java.util.UUID;

/**
 * 已保存的创建命令绑定；只含摘要与评论引用，不含正文或原始请求。
 *
 * @param operationDomain 固定操作域
 * @param requestId 请求标识
 * @param actorUserId 原操作者
 * @param documentId 原目标文档
 * @param versionId 原目标版本
 * @param parentCommentId 原父节点；根评论为空
 * @param bodyDigest 原规范化正文摘要
 * @param commentId 已提交的评论身份；同事务尚未完成时为空
 */
public record CommentCommandRow(String operationDomain, String requestId, long actorUserId, long documentId,
                                long versionId, UUID parentCommentId, String bodyDigest, UUID commentId) {
}
