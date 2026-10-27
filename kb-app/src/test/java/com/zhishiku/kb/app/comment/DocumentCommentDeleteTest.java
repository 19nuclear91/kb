package com.zhishiku.kb.app.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.comment.CommentFixture.Access;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.service.DocumentCommentService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.zhishiku.kb.app.comment.CommentFixture.ADMIN;
import static com.zhishiku.kb.app.comment.CommentFixture.AUTHOR;
import static com.zhishiku.kb.app.comment.CommentFixture.DOCUMENT;
import static com.zhishiku.kb.app.comment.CommentFixture.OTHER_VERSION;
import static com.zhishiku.kb.app.comment.CommentFixture.REPLIER;
import static com.zhishiku.kb.app.comment.CommentFixture.VERSION;
import static com.zhishiku.kb.app.comment.DocumentCommentCreateTest.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** 本人删除、幂等墓碑、根删除不级联及删除后的创建重放；真实服务与事务拦截器，仓储为内存替身。 */
class DocumentCommentDeleteTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final CommentFixture fixture = new CommentFixture();
    private final CommentFixture.Store store = fixture.store;

    @Test
    void ownDeleteClearsBodyKeepsStructureAndRepeatsIdempotently() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.as(REPLIER);
        var reply = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "回复", "req-reply");
        Instant at = fixture.tick();
        store.clearCalls();
        fixture.commands.delete(DOCUMENT, VERSION, reply.commentId());
        var saved = store.comment(reply.commentId());
        assertThat(saved.body).isNull();
        assertThat(saved.deletedAt).isEqualTo(at.truncatedTo(ChronoUnit.MICROS));
        assertThat(saved.rootId).isEqualTo(store.comment(root.commentId()).id);
        assertThat(saved.authorUserId).isEqualTo(REPLIER);
        assertThat(store.facts().getLast()).isEqualTo(CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED,
                DOCUMENT, VERSION, reply.commentId(), root.commentId(), REPLIER, at.truncatedTo(ChronoUnit.MICROS)));
        assertThat(store.recipients.get(store.facts().getLast().eventId())).isEmpty();
        assertThat(store.calls()).containsExactly("lockOwn", "erase", "startCommentCoverage", "append");
        Instant first = saved.deletedAt;
        int facts = store.facts().size();
        fixture.tick();
        store.clearCalls();
        fixture.commands.delete(DOCUMENT, VERSION, reply.commentId());
        assertThat(store.comment(reply.commentId()).deletedAt).isEqualTo(first);
        assertThat(store.facts()).hasSize(facts);
        assertThat(store.calls()).containsExactly("lockOwn");
        // 删除不撤销已确认的创建事实。
        assertThat(store.facts()).extracting(CollaborationEvent::kind).containsExactly(CollaborationKind.COMMENT_CREATED,
                CollaborationKind.COMMENT_REPLIED, CollaborationKind.COMMENT_DELETED);
    }

    @Test
    void authorCanDeleteAfterLosingViewAndCommentWithoutReadingTheTarget() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.view = Access.MISSING;
        fixture.locked = Access.MISSING;
        clearInvocations(fixture.targets, fixture.identities);
        store.clearCalls();
        fixture.commands.delete(DOCUMENT, VERSION, root.commentId());
        verifyNoInteractions(fixture.targets, fixture.identities);
        assertThat(store.calls()).doesNotContain("find", "roots", "replies", "lockRoot", "inspect", "lock");
        assertThat(store.comment(root.commentId()).body).isNull();
    }

    @Test
    void othersAdministratorsAndWrongPathsAllGetTheSameNotFound() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        var elsewhere = store.seed(DOCUMENT, OTHER_VERSION, null, AUTHOR, "另一版本", Instant.EPOCH);
        List<String> messages = new ArrayList<>();
        // 管理员不经任何管理资格代删：服务只按作者身份锁定本人评论。
        for (long user : List.of(REPLIER, ADMIN)) {
            fixture.as(user);
            messages.add(notFound(() -> fixture.commands.delete(DOCUMENT, VERSION, root.commentId())));
        }
        fixture.as(AUTHOR);
        messages.add(notFound(() -> fixture.commands.delete(DOCUMENT, VERSION, UUID.randomUUID())));
        messages.add(notFound(() -> fixture.commands.delete(DOCUMENT, OTHER_VERSION, root.commentId())));
        messages.add(notFound(() -> fixture.commands.delete(DOCUMENT + 1, VERSION, root.commentId())));
        messages.add(notFound(() -> fixture.commands.delete(DOCUMENT, VERSION, elsewhere.commentId())));
        assertThat(messages).containsOnly(messages.getFirst());
        assertThat(store.comment(root.commentId()).body).isEqualTo("根");
        assertThat(store.comment(elsewhere.commentId()).body).isEqualTo("另一版本");
        assertThat(store.facts()).hasSize(1);
        verifyNoInteractions(fixture.identities);
    }

    @Test
    void rootDeletionKeepsExistingRepliesButBlocksNewReplies() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "根", "req-root");
        fixture.as(REPLIER);
        var reply = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "他人回复", "req-reply");
        fixture.as(AUTHOR);
        fixture.commands.delete(DOCUMENT, VERSION, root.commentId());
        assertThat(store.comment(root.commentId()).body).isNull();
        assertThat(store.comment(reply.commentId()).body).isEqualTo("他人回复");
        assertThat(store.comment(reply.commentId()).deletedAt).isNull();
        fixture.as(REPLIER);
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "新回复", "req-new"), 409);
        // 原回复请求的匹配重放仍返回原身份，不重新套用“根必须未删”。
        var replay = fixture.commands.create(DOCUMENT, VERSION, root.commentId(), "他人回复", "req-reply");
        assertThat(replay.commentId()).isEqualTo(reply.commentId());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.deleted()).isFalse();
        assertThat(store.facts()).extracting(CollaborationEvent::kind).containsExactly(CollaborationKind.COMMENT_CREATED,
                CollaborationKind.COMMENT_REPLIED, CollaborationKind.COMMENT_DELETED);
    }

    @Test
    void replayAfterDeletionReturnsOnlyADeletedReceiptAndNeverRevivesTheBody() throws Exception {
        var created = fixture.commands.create(DOCUMENT, VERSION, null, "删除前正文", "req-1");
        fixture.tick();
        fixture.commands.delete(DOCUMENT, VERSION, created.commentId());
        fixture.tick();
        int facts = store.facts().size();
        store.clearCalls();
        var replay = fixture.commands.create(DOCUMENT, VERSION, null, "删除前正文", "req-1");
        assertThat(replay).isEqualTo(new CommentViews.Receipt(created.commentId(), null, DOCUMENT, VERSION,
                created.createdAt(), true, true));
        assertThat(store.calls()).doesNotContain("insert", "erase", "append", "complete");
        assertThat(store.comment(created.commentId()).body).isNull();
        assertThat(store.comment(created.commentId()).createdAt).isEqualTo(created.createdAt());
        assertThat(store.facts()).hasSize(facts);
        // 回执、事实与命令绑定均不含删除前正文。
        assertThat(JSON.writeValueAsString(replay)).doesNotContain("删除前正文");
        assertThat(JSON.writeValueAsString(store.facts())).doesNotContain("删除前正文");
        assertThat(store.facts().toString()).doesNotContain("删除前正文");
        // 不同正文仍为冲突，不能借重放探测或复活。
        assertStatus(() -> fixture.commands.create(DOCUMENT, VERSION, null, "另一正文", "req-1"), 409);
        // 服务不持有日志器，正文不会经日志输出。
        assertThat(Arrays.stream(DocumentCommentService.class.getDeclaredFields()))
                .noneMatch(field -> Logger.class.isAssignableFrom(field.getType()));
    }

    @Test
    void failuresDuringDeletionRestoreTheBodyAndAreUnavailable() {
        var root = fixture.commands.create(DOCUMENT, VERSION, null, "保留正文", "req-root");
        for (String step : List.of("erase", "startCommentCoverage", "append")) {
            store.fault = call -> {
                if (call.equals(step)) {
                    throw new IllegalStateException("private 保留正文");
                }
            };
            assertThatThrownBy(() -> fixture.commands.delete(DOCUMENT, VERSION, root.commentId())).as(step)
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.httpStatus()).isEqualTo(503);
                        assertThat(error.getMessage()).doesNotContain("保留正文");
                    });
            assertThat(store.comment(root.commentId()).body).isEqualTo("保留正文");
            assertThat(store.comment(root.commentId()).deletedAt).isNull();
        }
        store.fault = ignored -> { };
        for (String call : List.of("erase", "append")) {
            store.forced.clear();
            store.forced.put(call, 0);
            assertStatus(() -> fixture.commands.delete(DOCUMENT, VERSION, root.commentId()), 503);
            assertThat(store.comment(root.commentId()).body).isEqualTo("保留正文");
        }
        store.forced.clear();
        assertThat(store.facts()).hasSize(1);
        fixture.identityFailure = IdentityException.unauthorized("x");
        assertStatus(() -> fixture.commands.delete(DOCUMENT, VERSION, root.commentId()), 401);
        assertThat(store.comment(root.commentId()).body).isEqualTo("保留正文");
    }

    private static String notFound(Runnable action) {
        try {
            action.run();
        } catch (ApiException error) {
            assertThat(error.httpStatus()).isEqualTo(404);
            return error.getMessage();
        }
        throw new AssertionError("expected not found");
    }
}
