package com.zhishiku.kb.app.comment.service;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.comment.CommentProperties;
import com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import com.zhishiku.kb.app.document.service.DocumentCommentTargetService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 固定版本评论的有界读取。每次先核对当前版本资格，再校验游标；根评论倒序、回复正序各自分页，
 * 不嵌入回复全集，不返回隐藏总数。墓碑不返回原正文或作者标签。
 */
@Service
@Transactional(readOnly = true, timeout = 10)
public class DocumentCommentQueryService {
    static final String ROOTS = "document-comment-roots";
    static final String REPLIES = "document-comment-replies";
    private final AdminQueryAccess access;
    private final DocumentCommentTargetService targets;
    private final DocumentCommentMapper mapper;
    private final IdentityQueryService identities;
    private final CommentProperties properties;

    public DocumentCommentQueryService(AdminQueryAccess access, DocumentCommentTargetService targets,
                                       DocumentCommentMapper mapper, IdentityQueryService identities,
                                       CommentProperties properties) {
        this.access = access;
        this.targets = targets;
        this.mapper = mapper;
        this.identities = identities;
        this.properties = properties;
    }

    public CommentViews.Page roots(long documentId, long versionId, Map<String, List<String>> input) {
        return guarded(() -> {
            var handle = access.current();
            var parameters = new QueryParameters(input, "limit", "cursor");
            int limit = parameters.limit();
            String token = parameters.cursor();
            var target = readable(targets.inspect(handle, documentId, versionId));
            var filters = QueryParameters.filters("documentId", documentId, "versionId", versionId);
            var cursor = QueryCursor.decode(token, ROOTS);
            if (cursor != null) {
                cursor.verify(ROOTS, filters);
            }
            Long before = cursor == null ? null : cursor.id();
            var rows = mapper.roots(documentId, versionId, before, limit + 1);
            if (rows == null || rows.size() > limit + 1) {
                throw new IllegalStateException("Comment page is unavailable");
            }
            Long previous = before;
            for (var row : rows) {
                if (!belongs(row, documentId, versionId) || !row.root() || (previous != null && row.id() >= previous)) {
                    throw new IllegalStateException("Comment page is inconsistent");
                }
                previous = row.id();
            }
            boolean more = rows.size() > limit;
            var page = rows.subList(0, Math.min(limit, rows.size()));
            String next = more ? QueryCursor.encode(ROOTS, filters, page.getLast().id()) : null;
            return new CommentViews.Page(items(page, handle.userId()), more, next, creatable(target));
        });
    }

    public CommentViews.Replies replies(long documentId, long versionId, UUID rootId, Map<String, List<String>> input) {
        Objects.requireNonNull(rootId, "rootId");
        return guarded(() -> {
            var handle = access.current();
            var parameters = new QueryParameters(input, "limit", "cursor");
            int limit = parameters.limit();
            String token = parameters.cursor();
            var target = readable(targets.inspect(handle, documentId, versionId));
            var root = mapper.find(documentId, versionId, rootId);
            if (root == null || !root.root()) {
                throw ApiException.notFound("评论不存在或当前不可查看");
            }
            if (!belongs(root, documentId, versionId)) {
                throw new IllegalStateException("Comment root is inconsistent");
            }
            var filters = QueryParameters.filters("documentId", documentId, "versionId", versionId, "rootId",
                    root.commentId());
            var cursor = QueryCursor.decode(token, REPLIES);
            if (cursor != null) {
                cursor.verify(REPLIES, filters);
            }
            Long after = cursor == null ? null : cursor.id();
            var rows = mapper.replies(documentId, versionId, root.id(), after, limit + 1);
            if (rows == null || rows.size() > limit + 1) {
                throw new IllegalStateException("Reply page is unavailable");
            }
            Long previous = after;
            for (var row : rows) {
                if (!belongs(row, documentId, versionId) || !Objects.equals(row.rootId(), root.id())
                        || !root.commentId().equals(row.rootCommentId()) || (previous != null && row.id() <= previous)) {
                    throw new IllegalStateException("Reply page is inconsistent");
                }
                previous = row.id();
            }
            boolean more = rows.size() > limit;
            var page = rows.subList(0, Math.min(limit, rows.size()));
            String next = more ? QueryCursor.encode(REPLIES, filters, page.getLast().id()) : null;
            return new CommentViews.Replies(items(page, handle.userId()), more, next,
                    creatable(target) && !root.deleted());
        });
    }

    /** 固定评论目标：仍按版本当前资格读取，不替换为最新版本；用于回复通知定位。 */
    public CommentViews.Detail detail(long documentId, long versionId, UUID commentId, Map<String, List<String>> input) {
        Objects.requireNonNull(commentId, "commentId");
        return guarded(() -> {
            var handle = access.current();
            new QueryParameters(input);
            var target = readable(targets.inspect(handle, documentId, versionId));
            var row = mapper.find(documentId, versionId, commentId);
            if (row == null) {
                throw ApiException.notFound("评论不存在或当前不可查看");
            }
            if (!belongs(row, documentId, versionId)) {
                throw new IllegalStateException("Comment is inconsistent");
            }
            boolean rootLive;
            if (row.root()) {
                rootLive = !row.deleted();
            } else {
                var root = mapper.find(documentId, versionId, row.rootCommentId());
                if (root == null || !root.root() || root.id() != row.rootId()) {
                    throw new IllegalStateException("Comment root is inconsistent");
                }
                rootLive = !root.deleted();
            }
            var item = items(List.of(row), handle.userId()).getFirst();
            return new CommentViews.Detail(item, creatable(target) && rootLive);
        });
    }

    /** 批量取未删除评论作者的最小标签；墓碑不再解析旧作者。 */
    private List<CommentViews.Item> items(List<CommentRow> rows, long actor) {
        var ids = new HashSet<Long>();
        for (var row : rows) {
            if (!row.deleted()) {
                ids.add(row.authorUserId());
            }
        }
        var labels = identities.labels(ids);
        List<CommentViews.Item> items = new ArrayList<>(rows.size());
        var seen = new HashSet<UUID>();
        for (var row : rows) {
            if (!seen.add(row.commentId())) {
                throw new IllegalStateException("Comment page repeats an item");
            }
            if (row.deleted()) {
                items.add(new CommentViews.Item(row.commentId(), row.rootCommentId(), row.documentId(), row.versionId(),
                        row.createdAt(), true, null, null, false, false));
                continue;
            }
            String label = labels.get(row.authorUserId());
            if (label == null) {
                throw new IllegalStateException("Comment author is unavailable");
            }
            boolean own = row.authorUserId() == actor;
            items.add(new CommentViews.Item(row.commentId(), row.rootCommentId(), row.documentId(), row.versionId(),
                    row.createdAt(), false, new CommentViews.Author(row.authorUserId(), label), row.body(), own, own));
        }
        return items;
    }

    /** 界面提示与写入口同一规则：新建关闭时不提示可发表。 */
    private boolean creatable(DocumentCommentTarget target) {
        return properties.isCreationEnabled() && target.commentable();
    }

    private static boolean belongs(CommentRow row, long documentId, long versionId) {
        return row.documentId() == documentId && row.versionId() == versionId;
    }

    /** 不存在、归属不符、无 view、无发布证据、归档或删除统一 404，不回显资源明细。 */
    private static DocumentCommentTarget readable(DocumentCommentTarget target) {
        if (target == null || !target.readable()) {
            throw ApiException.notFound("文档或版本不存在，或当前不可查看");
        }
        return target;
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
