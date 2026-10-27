package com.zhishiku.kb.app.comment.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.comment.CommentProperties;
import com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper;
import com.zhishiku.kb.app.comment.model.CommentCommand;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.CommentText;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import com.zhishiku.kb.app.document.service.DocumentCommentTargetService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 本人评论的创建、单层回复与删除。锁序：command → document → root → child → event/receipt；
 * 源事实与回执同事务提交，不调用通知消费者，也不获取 Embedding 或解析任务锁。
 */
@Service
public class DocumentCommentService {
    private final AdminQueryAccess access;
    private final DocumentCommentTargetService targets;
    private final DocumentCommentMapper mapper;
    private final CollaborationEventService events;
    private final CommentProperties properties;
    private final Clock clock;

    @Autowired
    public DocumentCommentService(AdminQueryAccess access, DocumentCommentTargetService targets,
                                  DocumentCommentMapper mapper, CollaborationEventService events,
                                  CommentProperties properties) {
        this(access, targets, mapper, events, properties, Clock.systemUTC());
    }

    public DocumentCommentService(AdminQueryAccess access, DocumentCommentTargetService targets,
                                  DocumentCommentMapper mapper, CollaborationEventService events,
                                  CommentProperties properties, Clock clock) {
        this.access = access;
        this.targets = targets;
        this.mapper = mapper;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 创建根评论或回复。匹配重放重新核对当前读取／发表资格后返回原身份，不重新套用“根必须未删”；
     * 原评论已删时只返回 deleted 最小回执，不重新写正文或事件。
     */
    @Transactional(timeout = 10)
    public CommentViews.Receipt create(long documentId, long versionId, UUID parentId, String rawBody, String requestId) {
        String body = CommentText.normalize(rawBody);
        try {
            ReviewCommandBinding.requireRequestId(requestId);
        } catch (IllegalArgumentException invalid) {
            throw QueryException.invalid();
        }
        if (documentId <= 0 || versionId <= 0) {
            throw QueryException.invalid();
        }
        return guarded(() -> {
            var handle = access.current();
            var command = CommentCommand.create(requestId, handle.userId(), documentId, versionId, parentId, body);
            require(targets.inspect(handle, documentId, versionId));
            if (mapper.claim(command) != 1) {
                return replay(command);
            }
            require(targets.lock(handle, documentId, versionId));
            CommentRow root = null;
            if (parentId != null) {
                root = mapper.lockRoot(documentId, versionId, parentId);
                if (root == null) {
                    throw ApiException.notFound("评论不存在或当前不可查看");
                }
                if (!root.root() || root.deleted()) {
                    throw ApiException.conflict("STATE_CONFLICT");
                }
            }
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            UUID commentId = UUID.randomUUID();
            Long rootId = root == null ? null : root.id();
            Long id = mapper.insert(commentId, documentId, versionId, rootId, handle.userId(), body, now);
            if (id == null || id <= 0 || (rootId != null && id <= rootId)) {
                throw new IllegalStateException("Comment was not saved");
            }
            var event = root == null
                    ? CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, documentId, versionId, commentId, null,
                    handle.userId(), now)
                    : CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, documentId, versionId, commentId,
                    root.commentId(), handle.userId(), now);
            // 回复只提醒根评论作者；服务层排除本人，根创建与删除不产生个人提醒。
            List<Long> recipients = root == null ? List.of() : List.of(root.authorUserId());
            if (!events.append(event, recipients)) {
                throw new IllegalStateException("Comment fact already existed");
            }
            if (mapper.complete(command, commentId) != 1) {
                throw new IllegalStateException("Comment receipt was not saved");
            }
            return new CommentViews.Receipt(commentId, parentId, documentId, versionId, now, false, false);
        });
    }

    /** 本人删除：不要求当前 view 或 comment，不读取文档或其他评论；他人与不存在同为 404，本人重复删除幂等。 */
    @Transactional(timeout = 10)
    public void delete(long documentId, long versionId, UUID commentId) {
        Objects.requireNonNull(commentId, "commentId");
        guarded(() -> {
            var handle = access.current();
            var own = mapper.lockOwn(documentId, versionId, commentId, handle.userId());
            if (own == null) {
                throw ApiException.notFound("评论不存在");
            }
            if (own.documentId() != documentId || own.versionId() != versionId || !own.commentId().equals(commentId)) {
                throw new IllegalStateException("Own comment is inconsistent");
            }
            if (own.deleted()) {
                return null;
            }
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (mapper.erase(own.id(), now) != 1) {
                throw new IllegalStateException("Comment was not erased");
            }
            // 首次删除才写事实；删除不撤销已确认的创建事实，也不通知任何人。
            var fact = CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED, documentId, versionId, commentId,
                    own.rootCommentId(), handle.userId(), now);
            if (!events.append(fact, List.of())) {
                throw new IllegalStateException("Comment deletion fact already existed");
            }
            return null;
        });
    }

    private CommentViews.Receipt replay(CommentCommand command) {
        var saved = mapper.command(command.requestId());
        if (saved == null) {
            throw new IllegalStateException("Comment command is unavailable");
        }
        if (!command.matches(saved)) {
            throw ApiException.conflict("STATE_CONFLICT");
        }
        if (saved.commentId() == null) {
            throw new IllegalStateException("Comment receipt is unavailable");
        }
        var row = mapper.find(command.documentId(), command.versionId(), saved.commentId());
        if (row == null || row.authorUserId() != command.actorUserId()
                || !Objects.equals(row.rootCommentId(), command.parentId())) {
            throw new IllegalStateException("Comment receipt is inconsistent");
        }
        return new CommentViews.Receipt(row.commentId(), row.rootCommentId(), row.documentId(), row.versionId(),
                row.createdAt(), row.deleted(), true);
    }

    /**
     * 不存在、归属不符、无 view 或不再满足发布证据均为同一 404；可读但无 comment 或新建已关闭为 403。
     * 关闭开关不早于可读检查，避免借 403 探测不可见目标。
     */
    private void require(DocumentCommentTarget target) {
        if (target == null || !target.readable()) {
            throw ApiException.notFound("文档或版本不存在，或当前不可查看");
        }
        if (!properties.isCreationEnabled() || !target.commentable()) {
            throw ApiException.forbidden("当前不可发表评论");
        }
    }

    private static <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (ApiException | QueryException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("评论服务暂不可用");
        }
    }
}
