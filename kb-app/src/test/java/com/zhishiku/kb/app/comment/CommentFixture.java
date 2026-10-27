package com.zhishiku.kb.app.comment;

import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.IdentityQueryService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper;
import com.zhishiku.kb.app.comment.model.CommentCommand;
import com.zhishiku.kb.app.comment.model.CommentCommandRow;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.OwnComment;
import com.zhishiku.kb.app.comment.service.DocumentCommentQueryService;
import com.zhishiku.kb.app.comment.service.DocumentCommentService;
import com.zhishiku.kb.app.document.model.DocumentCommentTarget;
import com.zhishiku.kb.app.document.service.DocumentCommentTargetService;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评论命令与读取的独立测试替身：真实 Spring 事务拦截器 + 撤销日志式内存事务，资格与作者标签为 Mock。
 * 请求标识唯一绑定按“未决时等待、提交后可见、回滚后释放”复刻；不证明真实行锁、隔离级别或提交原子性。
 */
final class CommentFixture {
    static final long DOCUMENT = 10, VERSION = 20, OTHER_VERSION = 21;
    static final long AUTHOR = 7, REPLIER = 8, ADMIN = 9;
    static final Instant START = Instant.parse("2026-10-26T01:02:03.123456789Z");

    /** 当前主体在目标版本上的资格。 */
    enum Access {
        /** 文档或版本不存在、归属不符。 */
        MISSING,
        /** 有 comment 但无 view、无发布证据、升密或归档：同为不可读。 */
        HIDDEN,
        /** 可读但无 comment。 */
        READ,
        /** 可读可发表。 */
        COMMENT
    }

    final Store store = new Store();
    final Transactions transactions = new Transactions(store);
    final AdminQueryAccess access = mock(AdminQueryAccess.class);
    final DocumentCommentTargetService targets = mock(DocumentCommentTargetService.class);
    final IdentityQueryService identities = mock(IdentityQueryService.class);
    final AtomicReference<Instant> now = new AtomicReference<>(START);
    final List<Set<Long>> labelBatches = new CopyOnWriteArrayList<>();
    final Map<Long, PermissionService.PrincipalHandle> handles = new HashMap<>();
    volatile long actor = AUTHOR;
    volatile RuntimeException identityFailure;
    /** 版本、权限或授权依赖故障。 */
    volatile RuntimeException targetFailure;
    volatile Access view = Access.COMMENT;
    /** 写事务锁定文档后重读的资格；为空时与锁外读取一致。 */
    volatile Access locked;
    /** 写事务锁定文档时调用，用于编排并发顺序。 */
    volatile Runnable lockHook = () -> { };
    /** 运行开关；默认允许新建。 */
    final CommentProperties properties = new CommentProperties();
    final CollaborationEventService events;
    final DocumentCommentService commands;
    final DocumentCommentQueryService queries;

    CommentFixture() {
        for (long id : List.of(AUTHOR, REPLIER, ADMIN, 11L, 12L)) {
            var handle = mock(PermissionService.PrincipalHandle.class);
            when(handle.userId()).thenReturn(id);
            handles.put(id, handle);
        }
        when(access.current()).thenAnswer(call -> {
            if (identityFailure != null) {
                throw identityFailure;
            }
            return handles.get(actor);
        });
        when(targets.inspect(any(), anyLong(), anyLong())).thenAnswer(call -> {
            store.record("inspect");
            if (targetFailure != null) {
                throw targetFailure;
            }
            return target(view, call.getArgument(1), call.getArgument(2));
        });
        when(targets.lock(any(), anyLong(), anyLong())).thenAnswer(call -> {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("lock requires the caller's write transaction");
            }
            store.record("lock");
            lockHook.run();
            return target(locked == null ? view : locked, call.getArgument(1), call.getArgument(2));
        });
        when(identities.labels(anyCollection())).thenAnswer(call -> {
            Collection<Long> ids = call.getArgument(0);
            labelBatches.add(Set.copyOf(ids));
            Map<Long, String> labels = new HashMap<>();
            for (Long id : ids) {
                labels.put(id, "成员" + id);
            }
            return labels;
        });
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        events = transactional(new CollaborationEventService(store.events()), CollaborationEventService.class);
        commands = transactional(new DocumentCommentService(access, targets, store, events, properties, clock),
                DocumentCommentService.class);
        queries = new DocumentCommentQueryService(access, targets, store, identities, properties);
    }

    void as(long user) {
        actor = user;
    }

    Instant tick() {
        return now.updateAndGet(value -> value.plusSeconds(1));
    }

    private <T> T transactional(T target, Class<T> type) {
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        return type.cast(factory.getProxy());
    }

    static DocumentCommentTarget target(Access access, long documentId, long versionId) {
        if (access == Access.MISSING) {
            return null;
        }
        boolean view = access == Access.READ || access == Access.COMMENT;
        boolean comment = access == Access.HIDDEN || access == Access.COMMENT;
        return new DocumentCommentTarget(documentId, versionId, true, true, view, comment, PublicationPolicy.AUTO,
                PublicationProtocol.VERSIONED_REVIEW, 9L, PublicationEvidence.PUBLISHED, Instant.EPOCH);
    }

    /** 评论与命令绑定的内存仓储；所有状态由同一监视器保护，写入登记撤销动作。 */
    static final class Store implements DocumentCommentMapper {
        static final class Comment {
            long id;
            UUID commentId;
            long documentId;
            long versionId;
            Long rootId;
            long authorUserId;
            String body;
            Instant createdAt;
            Instant deletedAt;
        }

        static final class Command {
            CommentCommand binding;
            UUID commentId;
            Thread owner;
        }

        final Map<Long, Comment> comments = new LinkedHashMap<>();
        final Map<String, Command> commandRows = new LinkedHashMap<>();
        final Map<UUID, CollaborationEvent> facts = new LinkedHashMap<>();
        final Map<UUID, TreeSet<Long>> recipients = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final List<String> pages = new ArrayList<>();
        final Map<String, Integer> forced = new HashMap<>();
        volatile Consumer<String> fault = ignored -> { };
        Instant commentCoverage;
        int waiters;
        private final Set<String> businessKeys = new HashSet<>();
        private final ThreadLocal<Deque<Runnable>> journal = new ThreadLocal<>();
        private long sequence;

        synchronized void record(String call) {
            calls.add(call);
        }

        private void enter(String call) {
            calls.add(call);
            fault.accept(call);
        }

        private void undo(Runnable action) {
            var undo = journal.get();
            if (undo == null) {
                throw new IllegalStateException("write outside transaction");
            }
            undo.push(action);
        }

        private int result(String call, int value) {
            Integer override = forced.get(call);
            return override == null ? value : override;
        }

        synchronized void begin() {
            journal.set(new ArrayDeque<>());
        }

        synchronized void commit() {
            journal.remove();
            release();
        }

        synchronized void rollback() {
            var undo = journal.get();
            while (undo != null && !undo.isEmpty()) {
                undo.pop().run();
            }
            journal.remove();
            release();
        }

        private void release() {
            for (var command : commandRows.values()) {
                if (command.owner == Thread.currentThread()) {
                    command.owner = null;
                }
            }
            notifyAll();
        }

        /** 等待另一事务在同一请求标识上阻塞；用于确定并发时序。 */
        synchronized void awaitWaiters(int expected) {
            long deadline = System.currentTimeMillis() + 5_000;
            while (waiters < expected) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IllegalStateException("no concurrent claim arrived");
                }
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }

        @Override
        public synchronized int claim(CommentCommand command) {
            enter("claim");
            long deadline = System.currentTimeMillis() + 5_000;
            while (true) {
                var existing = commandRows.get(command.requestId());
                if (existing == null) {
                    var row = new Command();
                    row.binding = command;
                    row.owner = Thread.currentThread();
                    commandRows.put(command.requestId(), row);
                    undo(() -> commandRows.remove(command.requestId()));
                    return result("claim", 1);
                }
                if (existing.owner == null || existing.owner == Thread.currentThread()) {
                    return 0;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new IllegalStateException("claim wait timed out");
                }
                waiters++;
                notifyAll();
                try {
                    wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                } finally {
                    waiters--;
                }
            }
        }

        @Override
        public synchronized CommentCommandRow command(String requestId) {
            enter("command");
            var row = commandRows.get(requestId);
            if (row == null || (row.owner != null && row.owner != Thread.currentThread())) {
                return null;
            }
            var binding = row.binding;
            return new CommentCommandRow(binding.domain(), binding.requestId(), binding.actorUserId(),
                    binding.documentId(), binding.versionId(), binding.parentId(), binding.bodyDigest(), row.commentId);
        }

        @Override
        public synchronized int complete(CommentCommand command, UUID commentId) {
            enter("complete");
            var row = commandRows.get(command.requestId());
            if (row == null || row.commentId != null || row.binding.actorUserId() != command.actorUserId()
                    || !row.binding.bodyDigest().equals(command.bodyDigest())) {
                return result("complete", 0);
            }
            row.commentId = commentId;
            undo(() -> row.commentId = null);
            return result("complete", 1);
        }

        @Override
        public synchronized Long insert(UUID commentId, long documentId, long versionId, Long rootId, long authorUserId,
                                        String body, Instant createdAt) {
            enter("insert");
            if (rootId != null) {
                var root = comments.get(rootId);
                if (root == null || root.rootId != null || root.documentId != documentId || root.versionId != versionId) {
                    throw new IllegalStateException("root constraint");
                }
            }
            var comment = new Comment();
            comment.id = ++sequence;
            comment.commentId = commentId;
            comment.documentId = documentId;
            comment.versionId = versionId;
            comment.rootId = rootId;
            comment.authorUserId = authorUserId;
            comment.body = body;
            comment.createdAt = createdAt;
            comments.put(comment.id, comment);
            undo(() -> comments.remove(comment.id));
            return comment.id;
        }

        private Comment byId(long documentId, long versionId, UUID commentId) {
            return comments.values().stream().filter(item -> item.commentId.equals(commentId)
                    && item.documentId == documentId && item.versionId == versionId).findFirst().orElse(null);
        }

        private CommentRow row(Comment comment) {
            UUID root = comment.rootId == null ? null : comments.get(comment.rootId).commentId;
            return new CommentRow(comment.id, comment.commentId, comment.documentId, comment.versionId, comment.rootId,
                    root, comment.authorUserId, comment.body, comment.createdAt, comment.deletedAt);
        }

        @Override
        public synchronized CommentRow find(long documentId, long versionId, UUID commentId) {
            enter("find");
            var comment = byId(documentId, versionId, commentId);
            return comment == null ? null : row(comment);
        }

        @Override
        public synchronized CommentRow lockRoot(long documentId, long versionId, UUID commentId) {
            enter("lockRoot");
            var comment = byId(documentId, versionId, commentId);
            return comment == null ? null : row(comment);
        }

        @Override
        public synchronized List<CommentRow> roots(long documentId, long versionId, Long beforeId, int limit) {
            enter("roots");
            pages.add("roots:" + beforeId + ":" + limit);
            return comments.values().stream().filter(item -> item.documentId == documentId
                            && item.versionId == versionId && item.rootId == null
                            && (beforeId == null || item.id < beforeId))
                    .sorted(Comparator.comparingLong((Comment item) -> item.id).reversed())
                    .limit(limit).map(this::row).toList();
        }

        @Override
        public synchronized List<CommentRow> replies(long documentId, long versionId, long rootId, Long afterId,
                                                     int limit) {
            enter("replies");
            pages.add("replies:" + rootId + ":" + afterId + ":" + limit);
            return comments.values().stream().filter(item -> item.documentId == documentId
                            && item.versionId == versionId && item.rootId != null && item.rootId == rootId
                            && (afterId == null || item.id > afterId))
                    .sorted(Comparator.comparingLong(item -> item.id)).limit(limit).map(this::row).toList();
        }

        @Override
        public synchronized OwnComment lockOwn(long documentId, long versionId, UUID commentId, long authorUserId) {
            enter("lockOwn");
            var comment = byId(documentId, versionId, commentId);
            if (comment == null || comment.authorUserId != authorUserId) {
                return null;
            }
            UUID root = comment.rootId == null ? null : comments.get(comment.rootId).commentId;
            return new OwnComment(comment.id, comment.commentId, comment.documentId, comment.versionId, root,
                    comment.deletedAt != null);
        }

        @Override
        public synchronized int erase(long id, Instant deletedAt) {
            enter("erase");
            var comment = comments.get(id);
            if (comment == null || comment.deletedAt != null) {
                return result("erase", 0);
            }
            String body = comment.body;
            comment.body = null;
            comment.deletedAt = deletedAt.isBefore(comment.createdAt) ? comment.createdAt : deletedAt;
            undo(() -> {
                comment.body = body;
                comment.deletedAt = null;
            });
            return result("erase", 1);
        }

        /** 与评论共享事务与撤销日志的协作事实仓储。 */
        CollaborationEventMapper events() {
            return new CollaborationEventMapper() {
                @Override
                public int append(CollaborationEvent event) {
                    synchronized (Store.this) {
                        enter("append");
                        if (!businessKeys.add(event.businessKey())) {
                            return result("append", 0);
                        }
                        facts.put(event.eventId(), event);
                        recipients.put(event.eventId(), new TreeSet<>());
                        undo(() -> {
                            businessKeys.remove(event.businessKey());
                            facts.remove(event.eventId());
                            recipients.remove(event.eventId());
                        });
                        return result("append", 1);
                    }
                }

                @Override
                public int recipient(UUID eventId, long userId) {
                    synchronized (Store.this) {
                        enter("recipient");
                        if (!recipients.get(eventId).add(userId)) {
                            return 0;
                        }
                        undo(() -> recipients.get(eventId).remove(userId));
                        return result("recipient", 1);
                    }
                }

                @Override
                public int startCoverage(Instant ignored) {
                    synchronized (Store.this) {
                        enter("startCoverage");
                        return 0;
                    }
                }

                @Override
                public int startCommentCoverage(Instant at) {
                    synchronized (Store.this) {
                        enter("startCommentCoverage");
                        if (commentCoverage != null) {
                            return result("startCommentCoverage", 0);
                        }
                        commentCoverage = at;
                        undo(() -> commentCoverage = null);
                        return result("startCommentCoverage", 1);
                    }
                }
            };
        }

        /** 直接写入已提交评论，供读取测试布置数据；不经事务、不写事实。 */
        synchronized CommentRow seed(long documentId, long versionId, CommentRow root, long author, String body,
                                     Instant createdAt) {
            var comment = new Comment();
            comment.id = ++sequence;
            comment.commentId = UUID.nameUUIDFromBytes(("comment-" + comment.id).getBytes());
            comment.documentId = documentId;
            comment.versionId = versionId;
            comment.rootId = root == null ? null : root.id();
            comment.authorUserId = author;
            comment.body = body;
            comment.createdAt = createdAt;
            comments.put(comment.id, comment);
            return row(comment);
        }

        synchronized void tombstone(UUID commentId, Instant at) {
            var comment = comments.values().stream().filter(item -> item.commentId.equals(commentId)).findFirst()
                    .orElseThrow();
            comment.body = null;
            comment.deletedAt = at;
        }

        synchronized Comment comment(UUID commentId) {
            return comments.values().stream().filter(item -> item.commentId.equals(commentId)).findFirst()
                    .orElse(null);
        }

        synchronized List<CollaborationEvent> facts() {
            return List.copyOf(facts.values());
        }

        synchronized List<String> calls() {
            return List.copyOf(calls);
        }

        /** 已提交或本事务可见的请求标识。 */
        synchronized List<String> commandKeys() {
            return List.copyOf(commandRows.keySet());
        }

        /** 请求标识绑定的评论；未完成或不存在为空。 */
        synchronized UUID completed(String requestId) {
            var row = commandRows.get(requestId);
            return row == null ? null : row.commentId;
        }

        synchronized List<String> pages() {
            return List.copyOf(pages);
        }

        synchronized void clearCalls() {
            calls.clear();
            pages.clear();
        }
    }

    /** 每个外层事务开启撤销日志；参与事务的失败只标记回滚，由外层统一撤销。 */
    static final class Transactions extends AbstractPlatformTransactionManager {
        private final Store store;
        private final ThreadLocal<Boolean> active = new ThreadLocal<>();
        final List<Integer> timeouts = new CopyOnWriteArrayList<>();
        final List<Boolean> readOnly = new CopyOnWriteArrayList<>();
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();

        Transactions(Store store) {
            this.store = store;
        }

        private static final class Holder {
            private boolean existing;
        }

        @Override
        protected Object doGetTransaction() {
            var holder = new Holder();
            holder.existing = Boolean.TRUE.equals(active.get());
            return holder;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((Holder) transaction).existing;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
            timeouts.add(definition.getTimeout());
            readOnly.add(definition.isReadOnly());
            store.begin();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
            store.commit();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
            store.rollback();
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            // 参与者失败由外层事务统一回滚。
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.remove();
        }
    }
}
