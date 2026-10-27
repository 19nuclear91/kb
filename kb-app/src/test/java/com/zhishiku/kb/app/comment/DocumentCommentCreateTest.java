package com.zhishiku.kb.app.comment;

import com.zhishiku.kb.app.collaboration.service.CollaborationEventService;
import com.zhishiku.kb.app.comment.CommentFixture.Access;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.service.DocumentCommentService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.security.IdentityException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zhishiku.kb.app.comment.CommentFixture.AUTHOR;
import static com.zhishiku.kb.app.comment.CommentFixture.DOCUMENT;
import static com.zhishiku.kb.app.comment.CommentFixture.OTHER_VERSION;
import static com.zhishiku.kb.app.comment.CommentFixture.REPLIER;
import static com.zhishiku.kb.app.comment.CommentFixture.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 本人根评论与单层回复：真实服务、真实 Spring 事务拦截器与内存撤销日志；资格与身份为 Mock。
 * 并发分支只验证“同一请求标识先等待未决绑定、再按提交结果重放”的服务路径，不证明 PostgreSQL 行锁与隔离级别。
 */
class DocumentCommentCreateTest {
    private final CommentFixture fixture = new CommentFixture();
    private final CommentFixture.Store store = fixture.store;

    @Test
    void rootCreationUsesServerIdentityAndTimeAndCommitsFactWithReceipt() {
        var receipt = fixture.commands.create(DOCUMENT, VERSION, null, "  第一行\n第二行  ", "req-root");
        Instant at = CommentFixture.START.truncatedTo(ChronoUnit.MICROS);
        assertThat(receipt).isEqualTo(new CommentViews.Receipt(receipt.commentId(), null, DOCUMENT, VERSION, at, false,
                false));
        var saved = store.comment(receipt.commentId());
        assertThat(saved.authorUserId).isEqualTo(AUTHOR);
        assertThat(saved.body).isEqualTo("第一行\n第二行");
        assertThat(saved.rootId).isNull();
        assertThat(saved.createdAt).isEqualTo(at);
        var fact = store.facts().getFirst();
        assertThat(fact).isEqualTo(CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, DOCUMENT, VERSION,
                receipt.commentId(), null, AUTHOR, at));
        assertThat(store.recipients.get(fact.eventId())).isEmpty();
        assertThat(store.commentCoverage).isEqualTo(at);
        assertThat(store.completed("req-root")).isEqualTo(receipt.commentId());
        // 锁序：命令 → 文档 → 评论 → 事实 → 回执；不使用审核覆盖。
        assertThat(store.calls()).containsExactly("inspect", "claim", "lock", "insert", "startCommentCoverage", "append",
                "complete");
        assertThat(fixture.transactions.commits.get()).isEqualTo(1);
        assertThat(fixture.transactions.rollbacks.get()).isZero();
    }

    @Test
    void replyNotifiesOnlyTheRootAuthorAndSelfReplyRecordsNoRecipient() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.as(REPLIER);
        fixture.tick();
        store.clearCalls();
        var reply = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "回复", "req-reply");
        assertThat(reply.parentId()).isEqualTo(root.commentId());
        assertThat(store.comment(reply.commentId()).rootId).isEqualTo(store.comment(root.commentId()).id);
        var fact = store.facts().getLast();
        assertThat(fact.kind()).isEqualTo(CollaborationKind.COMMENT_REPLIED);
        assertThat(fact.rootCommentId()).isEqualTo(root.commentId());
        assertThat(fact.actorUserId()).isEqualTo(REPLIER);
        assertThat(store.recipients.get(fact.eventId())).containsExactly(AUTHOR);
        assertThat(store.calls()).containsExactly("inspect", "claim", "lock", "lockRoot", "insert",
                "startCommentCoverage", "append", "recipient", "complete");
        fixture.as(AUTHOR);
        var self = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "自己补充", "req-self");
        var selfFact = store.facts().getLast();
        assertThat(selfFact.commentId()).isEqualTo(self.commentId());
        assertThat(store.recipients.get(selfFact.eventId())).isEmpty();
        // 覆盖起点只由首个评论事实写入。
        assertThat(store.commentCoverage).isEqualTo(CommentFixture.START.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void matchingRetryReauthorizesAndReturnsTheOriginalIdentityWithoutWriting() {
        var first = fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-1");
        fixture.tick();
        store.clearCalls();
        var replay = fixture.commands.create(DOCUMENT, VERSION, null, " 正文\n", "req-1");
        assertThat(replay).isEqualTo(new CommentViews.Receipt(first.commentId(), null, DOCUMENT, VERSION,
                first.createdAt(), false, true));
        assertThat(store.calls()).containsExactly("inspect", "claim", "command", "find");
        assertThat(store.comments).hasSize(1);
        assertThat(store.facts()).hasSize(1);
        // 重放不沿用原授权。
        fixture.view = Access.HIDDEN;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-1"), 404);
        fixture.view = Access.READ;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-1"), 403);
        fixture.view = Access.COMMENT;
        assertThat(fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-1").replayed()).isTrue();
    }

    @Test
    void sameRequestIdWithDifferentBodyParentVersionOrActorConflictsWithoutWriting() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "回复", "req-1");
        int comments = store.comments.size(), facts = store.facts().size();
        List<ThrowingCallable> conflicts = List.of(
                () -> fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "另一段", "req-1"),
                () -> fixture.commands.create(DOCUMENT, VERSION, null, "回复", "req-1"),
                () -> fixture.commands.create(DOCUMENT, OTHER_VERSION, root.commentId(), "回复", "req-1"),
                () -> {
                    fixture.as(REPLIER);
                    fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "回复", "req-1");
                });
        for (var conflict : conflicts) {
            assertThatThrownBy(conflict).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.httpStatus()).isEqualTo(409);
                assertThat(error.getMessage()).doesNotContain(root.commentId().toString());
            });
            fixture.as(AUTHOR);
        }
        assertThat(store.comments).hasSize(comments);
        assertThat(store.facts()).hasSize(facts);
    }

    @Test
    void concurrentMatchingRequestsWaitOnTheBindingAndProduceOneCommentAndOneFact() throws Exception {
        var gate = new AtomicBoolean(true);
        fixture.lockHook = () -> {
            if (gate.getAndSet(false)) {
                store.awaitWaiters(1);
            }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> fixture.commands.create(DOCUMENT, VERSION, null, "并发正文", "req-race"));
            var second = pool.submit(() -> fixture.commands.create(DOCUMENT, VERSION, null, "并发正文", "req-race"));
            var receipts = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(receipts).extracting(CommentViews.Receipt::commentId).containsOnly(receipts.getFirst().commentId());
            assertThat(receipts).extracting(CommentViews.Receipt::replayed).containsExactlyInAnyOrder(false, true);
        }
        assertThat(store.comments).hasSize(1);
        assertThat(store.facts()).hasSize(1);
        assertThat(fixture.transactions.commits.get()).isEqualTo(2);
    }

    @Test
    void waiterCreatesItsOwnCommentWhenTheFirstBindingRollsBack() throws Exception {
        var gate = new AtomicBoolean(true);
        var failOnce = new AtomicBoolean(true);
        fixture.lockHook = () -> {
            if (gate.getAndSet(false)) {
                store.awaitWaiters(1);
            }
        };
        store.fault = call -> {
            if (call.equals("complete") && failOnce.getAndSet(false)) {
                throw new IllegalStateException("receipt write failed");
            }
        };
        List<Object> outcomes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-race"));
            var second = pool.submit(() -> fixture.commands.create(DOCUMENT, VERSION, null, "正文", "req-race"));
            for (var future : List.of(first, second)) {
                try {
                    outcomes.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException failure) {
                    outcomes.add(failure.getCause());
                }
            }
        }
        var receipt = outcomes.stream().filter(CommentViews.Receipt.class::isInstance).map(CommentViews.Receipt.class::cast)
                .findFirst().orElseThrow();
        assertThat(receipt.replayed()).isFalse();
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(error -> assertThat(((ApiException) error).httpStatus()).isEqualTo(503));
        assertThat(store.comments.values()).singleElement()
                .satisfies(comment -> assertThat(comment.commentId).isEqualTo(receipt.commentId()));
        assertThat(store.facts()).singleElement()
                .satisfies(fact -> assertThat(fact.commentId()).isEqualTo(receipt.commentId()));
        assertThat(store.completed("req-race")).isEqualTo(receipt.commentId());
        assertThat(fixture.transactions.rollbacks.get()).isEqualTo(1);
    }

    @Test
    void deepCrossVersionMissingOrDeletedParentsAreRejectedAndRolledBack() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        var reply = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "回复", "req-reply");
        var elsewhere = store.seed(DOCUMENT, OTHER_VERSION, null, AUTHOR, "另一版本", Instant.EPOCH);
        var deleted = fixture.commands.create(DOCUMENT, VERSION, null, "将删除", "req-deleted");
        fixture.commands.delete(DOCUMENT, VERSION, deleted.commentId());
        int facts = store.facts().size();
        var keys = store.commandKeys();
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, reply.commentId(), "深层", "req-a"), 409);
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, elsewhere.commentId(), "跨版本", "req-b"), 404);
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, UUID.randomUUID(), "不存在", "req-c"), 404);
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, deleted.commentId(), "已删根", "req-d"), 409);
        assertThat(store.facts()).hasSize(facts);
        assertThat(store.commandKeys()).isEqualTo(keys);
        assertThat(store.comments.values()).extracting(comment -> comment.body)
                .doesNotContain("深层", "跨版本", "不存在", "已删根");
    }

    @Test
    void unreadableIsNotFoundReadOnlyIsForbiddenAndLockedRecheckRollsBackTheBinding() {
        fixture.view = Access.MISSING;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 404);
        fixture.view = Access.HIDDEN;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 404);
        fixture.view = Access.READ;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 403);
        assertThat(store.calls()).doesNotContain("claim");
        fixture.view = Access.COMMENT;
        fixture.locked = Access.HIDDEN;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 404);
        fixture.locked = Access.READ;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 403);
        assertThat(store.commandKeys()).isEmpty();
        assertThat(store.comments).isEmpty();
        assertThat(store.facts()).isEmpty();
    }

    @Test
    void creationSwitchStopsNewCommentsRepliesAndReplaysButKeepsReadsAndOwnDeletion() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        var own = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "本人回复", "req-own");
        int facts = store.facts().size();
        fixture.properties.setCreationEnabled(false);
        store.clearCalls();
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "新根", "req-new"), 403);
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "新回复", "req-reply"), 403);
        // 关闭期间匹配重放同样不放行；不写入绑定，也不在可读检查之前泄露目标是否存在。
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root"), 403);
        assertThat(store.calls()).doesNotContain("claim", "insert", "append");
        fixture.view = Access.HIDDEN;
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "新根", "req-new"), 404);
        fixture.view = Access.COMMENT;
        // 读取与界面提示同时关闭发表，但正文与本人删除不受影响。
        var page = fixture.queries.roots(DOCUMENT, VERSION, Map.of());
        assertThat(page.canCreate()).isFalse();
        assertThat(page.items()).extracting(CommentViews.Item::body).containsExactly("根");
        assertThat(fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of()).canCreate()).isFalse();
        assertThat(fixture.queries.detail(DOCUMENT, VERSION, root.commentId(), Map.of()).canReply()).isFalse();
        fixture.commands.delete(DOCUMENT, VERSION, own.commentId());
        assertThat(store.comment(own.commentId()).body).isNull();
        assertThat(store.facts()).hasSize(facts + 1);
        assertThat(store.commandKeys()).containsExactly("req-root", "req-own");
        // 重新开启后，原请求标识仍按首次绑定重放。
        fixture.properties.setCreationEnabled(true);
        assertThat(fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root"))
                .isEqualTo(new CommentViews.Receipt(root.commentId(), null, DOCUMENT, VERSION, root.createdAt(), false, true));
    }

    @Test
    void identityFailuresKeepTheirStatusAndDependencyFailuresAreUnavailable() {
        fixture.identityFailure = IdentityException.unauthorized("x");
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 401);
        fixture.identityFailure = IdentityException.passwordChangeRequired("x");
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 403);
        fixture.identityFailure = QueryException.forbidden();
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"), 403);
        fixture.identityFailure = null;
        fixture.targetFailure = new IllegalStateException("acl down");
        assertThatThrownBy(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.httpStatus()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("acl");
                });
        assertThat(store.commandKeys()).isEmpty();
    }

    @Test
    void invalidBodyRequestIdOrPathFailBeforeIdentityOrEligibility() {
        for (String body : new String[]{null, " \n ", "a".repeat(4001), "a\u0000"}) {
            assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, body, "req-1"), 400);
        }
        for (String requestId : new String[]{null, "", "a".repeat(129), "a\nb"}) {
            assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "x", requestId), 400);
        }
        assertStatus(() -> fixture.commands.create(0, VERSION, null, "x", "req-1"), 400);
        assertStatus(() -> fixture.commands.create(DOCUMENT, -1, null, "x", "req-1"), 400);
        verifyNoInteractions(fixture.access, fixture.targets);
        assertThat(store.calls()).isEmpty();
    }

    @Test
    void anyFailureAfterTheBodyIsWrittenRollsBackEverythingAndNeverReportsSuccess() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.as(REPLIER);
        for (String step : List.of("insert", "startCommentCoverage", "append", "recipient", "complete")) {
            store.fault = call -> {
                if (call.equals(step)) {
                    throw new IllegalStateException("private sql 秘密回复");
                }
            };
            assertThatThrownBy(() -> fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "秘密回复", "req-" + step))
                    .as(step).isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.httpStatus()).isEqualTo(503);
                        assertThat(error.getMessage()).doesNotContain("秘密回复").doesNotContain("private");
                    });
        }
        store.fault = ignored -> { };
        // 事实已存在、回执未写入或覆盖返回异常行数同样不能确认成功。
        for (String call : List.of("append", "complete", "startCommentCoverage", "recipient")) {
            store.forced.clear();
            store.forced.put(call, call.equals("startCommentCoverage") ? 2 : 0);
            assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "秘密回复", "req-f-" + call), 503);
        }
        store.forced.clear();
        assertThat(store.comments).hasSize(1);
        assertThat(store.facts()).hasSize(1);
        assertThat(store.commandKeys()).containsExactly("req-root");
    }

    @Test
    void transactionsAreBoundedAndFactsCannotBeAppendedOutsideTheSourceTransaction() throws Exception {
        for (var method : List.of(DocumentCommentService.class.getMethod("create", long.class, long.class, UUID.class,
                String.class, String.class), DocumentCommentService.class.getMethod("delete", long.class, long.class,
                UUID.class))) {
            var transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional.timeout()).as(method.getName()).isEqualTo(10);
            assertThat(transactional.readOnly()).isFalse();
            assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
        }
        assertThat(CollaborationEventService.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        var orphan = CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, DOCUMENT, VERSION, UUID.randomUUID(),
                null, AUTHOR, Instant.EPOCH);
        assertThatThrownBy(() -> fixture.events.append(orphan, List.of())).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(store.facts()).isEmpty();
        fixture.commands.create(DOCUMENT, VERSION, null, "x", "req-1");
        assertThat(fixture.transactions.timeouts).containsExactly(10);
    }

    static void assertStatus(ThrowingCallable action, int status) {
        assertThatThrownBy(action).satisfies(error -> assertThat(status(error)).isEqualTo(status));
    }

    static int status(Throwable error) {
        if (error instanceof ApiException api) {
            return api.httpStatus();
        }
        if (error instanceof QueryException query) {
            return query.status();
        }
        throw new AssertionError("unexpected failure " + error.getClass().getName(), error);
    }
}
