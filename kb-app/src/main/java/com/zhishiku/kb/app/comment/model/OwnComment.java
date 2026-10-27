package com.zhishiku.kb.app.comment.model;

import java.util.Objects;
import java.util.UUID;

/**
 * 本人删除使用的最小行锁投影；不读取正文、文档标题或其他评论。
 *
 * @param id 内部位置
 * @param commentId 对外评论身份
 * @param documentId 固定文档
 * @param versionId 固定版本
 * @param rootCommentId 回复所属根评论；根评论为空
 * @param deleted 是否已删除
 */
public record OwnComment(long id, UUID commentId, long documentId, long versionId, UUID rootCommentId, boolean deleted) {
    public OwnComment {
        Objects.requireNonNull(commentId, "commentId");
        if (id <= 0 || documentId <= 0 || versionId <= 0 || commentId.equals(rootCommentId)) {
            throw new IllegalArgumentException("Invalid own comment");
        }
    }
}
