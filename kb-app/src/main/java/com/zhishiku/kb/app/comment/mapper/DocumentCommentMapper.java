package com.zhishiku.kb.app.comment.mapper;

import com.zhishiku.kb.app.comment.model.CommentCommand;
import com.zhishiku.kb.app.comment.model.CommentCommandRow;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.OwnComment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 评论正文、结构与命令绑定；所有读取都按完整 documentId/versionId 路径限定，不跨版本取数。 */
@Mapper
public interface DocumentCommentMapper {
    /** @return 1 为新绑定；0 为请求标识已存在，须用独立语句读取，不能在已中止事务中重试 */
    int claim(CommentCommand command);

    CommentCommandRow command(@Param("requestId") String requestId);

    int complete(@Param("command") CommentCommand command, @Param("commentId") UUID commentId);

    /** @return 新评论的内部单调位置 */
    Long insert(@Param("commentId") UUID commentId, @Param("documentId") long documentId,
                @Param("versionId") long versionId, @Param("rootId") Long rootId,
                @Param("authorUserId") long authorUserId, @Param("body") String body,
                @Param("createdAt") Instant createdAt);

    CommentRow find(@Param("documentId") long documentId, @Param("versionId") long versionId,
                    @Param("commentId") UUID commentId);

    /** 回复事务对父节点取共享锁；根删除取排他锁，二者只能一前一后提交。 */
    CommentRow lockRoot(@Param("documentId") long documentId, @Param("versionId") long versionId,
                        @Param("commentId") UUID commentId);

    List<CommentRow> roots(@Param("documentId") long documentId, @Param("versionId") long versionId,
                           @Param("beforeId") Long beforeId, @Param("limit") int limit);

    List<CommentRow> replies(@Param("documentId") long documentId, @Param("versionId") long versionId,
                             @Param("rootId") long rootId, @Param("afterId") Long afterId, @Param("limit") int limit);

    /** 只按完整路径与作者查找本人评论；他人、管理员与不存在目标同样无返回行。 */
    OwnComment lockOwn(@Param("documentId") long documentId, @Param("versionId") long versionId,
                       @Param("commentId") UUID commentId, @Param("authorUserId") long authorUserId);

    int erase(@Param("id") long id, @Param("deletedAt") Instant deletedAt);
}
