package com.zhishiku.kb.app.comment.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 评论正文、单层结构与墓碑的持久化投影；不复制文档标题、文件名或其他评论内容。
 *
 * @param id 内部单调位置，只用于排序与游标，不对外输出
 * @param commentId 对外评论身份
 * @param documentId 固定文档
 * @param versionId 固定版本
 * @param rootId 回复所属根评论的内部位置；根评论为空
 * @param rootCommentId 回复所属根评论的对外身份；根评论为空
 * @param authorUserId 服务端确认的作者
 * @param body 未删除时的纯文本正文；删除后为空
 * @param createdAt 服务端创建时间
 * @param deletedAt 本人首次删除时间；未删除为空
 */
public record CommentRow(long id, UUID commentId, long documentId, long versionId, Long rootId, UUID rootCommentId,
                         long authorUserId, String body, Instant createdAt, Instant deletedAt) {
    public CommentRow {
        Objects.requireNonNull(commentId, "commentId");
        Objects.requireNonNull(createdAt, "createdAt");
        if (id <= 0 || documentId <= 0 || versionId <= 0 || authorUserId <= 0
                || (rootId == null) != (rootCommentId == null) || (rootId != null && (rootId <= 0 || rootId >= id))
                || commentId.equals(rootCommentId) || (body == null) != (deletedAt != null)) {
            throw new IllegalArgumentException("Invalid comment row");
        }
    }

    public boolean root() {
        return rootId == null;
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    /** 不在日志或异常中输出正文。 */
    @Override
    public String toString() {
        return "CommentRow{id=" + id + ", root=" + root() + ", deleted=" + deleted() + "}";
    }
}
