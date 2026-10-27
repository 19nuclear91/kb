package com.zhishiku.kb.app.comment.model;

import com.zhishiku.kb.common.review.ReviewCommandBinding;

import java.util.Objects;
import java.util.UUID;

/**
 * 创建命令绑定：规范化正文只参与摘要，绑定和回执均不保存正文副本。
 *
 * @param requestId 1–128 个非控制 code point 的请求标识
 * @param actorUserId 服务端会话中的操作者
 * @param documentId 固定文档
 * @param versionId 固定版本
 * @param parentId 回复的根评论；根评论为空
 * @param bodyDigest 规范化正文的长度前缀 SHA-256 摘要
 */
public record CommentCommand(String requestId, long actorUserId, long documentId, long versionId, UUID parentId,
                             String bodyDigest) {
    public static final String DOMAIN = "COMMENT_CREATE";

    public CommentCommand {
        ReviewCommandBinding.requireRequestId(requestId);
        if (actorUserId <= 0 || documentId <= 0 || versionId <= 0 || bodyDigest == null
                || !bodyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid comment command");
        }
    }

    /** 绑定域、操作者、目标版本、父节点与规范化正文；正文本身不进入记录。 */
    public static CommentCommand create(String requestId, long actor, long documentId, long versionId, UUID parentId,
                                        String normalizedBody) {
        return new CommentCommand(requestId, actor, documentId, versionId, parentId,
                ReviewCommandBinding.digest(DOMAIN, Objects.requireNonNull(normalizedBody, "body")));
    }

    public String domain() {
        return DOMAIN;
    }

    /** 同一请求标识的已保存绑定是否就是本次命令；任一字段不同均为冲突，不返回他人回执。 */
    public boolean matches(CommentCommandRow saved) {
        return saved != null && DOMAIN.equals(saved.operationDomain()) && requestId.equals(saved.requestId())
                && saved.actorUserId() == actorUserId && saved.documentId() == documentId && saved.versionId() == versionId
                && Objects.equals(saved.parentCommentId(), parentId) && bodyDigest.equals(saved.bodyDigest());
    }
}
