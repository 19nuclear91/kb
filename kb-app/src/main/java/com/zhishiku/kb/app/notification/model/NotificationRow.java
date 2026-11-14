package com.zhishiku.kb.app.notification.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 列表读取的最小事实投影；只包含可见性规则需要的固定状态与授权判定，不含标题、文件名、原因或评论正文。
 *
 * @param id 内部单调位置
 * @param notificationId 通知 UUID
 * @param recipientUserId 收件人
 * @param type 通知类型名称
 * @param createdAt 通知生成时间
 * @param readAt 首次已读时间
 * @param documentId 源事件文档
 * @param versionId 源事件固定版本
 * @param reviewId 源事件审核轮次
 * @param roundNo 源事件轮次序号
 * @param live 文档未删除未归档且空间未归档
 * @param cleared 当前密级满足文档密级
 * @param viewable 当前范围可交互读取该固定版本
 * @param actionable 收件人当前可处理该轮次，与审核工作台待办同一 SQL 条件；无轮次时为假
 * @param roundState 源轮次当前状态
 * @param roundSubmittedBy 源轮次送审者
 * @param roundInSpace 源轮次提交空间仍为文档当前空间
 * @param uploaderUserId 版本上传者；旧版本可空
 * @param publicationPolicy 版本固定策略
 * @param publicationEvidence 版本发布证据
 * @param commentId 源事件评论标识；审核事件为空
 * @param discussable 当前 view 范围可读取该固定版本的讨论，与评论目标同一发布证据片段
 * @param replyReadable 源事件是同一固定版本中仍未删除的回复，与评论模块同一 SQL 片段
 */
public record NotificationRow(long id, UUID notificationId, long recipientUserId, String type, Instant createdAt,
                              Instant readAt, Long documentId, Long versionId, UUID reviewId, Integer roundNo,
                              boolean live, boolean cleared, boolean viewable, boolean actionable, String roundState,
                              Long roundSubmittedBy, boolean roundInSpace, Long uploaderUserId,
                              String publicationPolicy, String publicationEvidence, UUID commentId,
                              boolean discussable, boolean replyReadable, GapNotificationFact gap) {
    /** 旧来源构造调用保持不变，资料与问题来源从不混合。 */
    public NotificationRow(long id, UUID notificationId, long recipientUserId, String type, Instant createdAt,
                           Instant readAt, long documentId, long versionId, UUID reviewId, Integer roundNo,
                           boolean live, boolean cleared, boolean viewable, boolean actionable, String roundState,
                           Long roundSubmittedBy, boolean roundInSpace, Long uploaderUserId, String publicationPolicy,
                           String publicationEvidence, UUID commentId, boolean discussable, boolean replyReadable) {
        this(id, notificationId, recipientUserId, type, createdAt, readAt, documentId, versionId, reviewId, roundNo,
                live, cleared, viewable, actionable, roundState, roundSubmittedBy, roundInSpace, uploaderUserId,
                publicationPolicy, publicationEvidence, commentId, discussable, replyReadable, null);
    }

    public static NotificationRow knowledgeGap(long id, UUID notificationId, long recipient, String type,
                                               Instant createdAt, Instant readAt, GapNotificationFact gap) {
        return new NotificationRow(id, notificationId, recipient, type, createdAt, readAt, null, null, null, null,
                false, false, false, false, null, null, false, null, null, null, null, false, false, gap);
    }
}
