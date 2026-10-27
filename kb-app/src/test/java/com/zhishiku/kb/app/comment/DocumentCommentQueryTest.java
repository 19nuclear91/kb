package com.zhishiku.kb.app.comment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.comment.CommentFixture.Access;
import com.zhishiku.kb.app.comment.mapper.DocumentCommentMapper;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.service.DocumentCommentQueryService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.zhishiku.kb.app.comment.CommentFixture.AUTHOR;
import static com.zhishiku.kb.app.comment.CommentFixture.DOCUMENT;
import static com.zhishiku.kb.app.comment.CommentFixture.OTHER_VERSION;
import static com.zhishiku.kb.app.comment.CommentFixture.REPLIER;
import static com.zhishiku.kb.app.comment.CommentFixture.VERSION;
import static com.zhishiku.kb.app.comment.DocumentCommentCreateTest.assertStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 根倒序、回复正序的独立有界分页与固定评论目标；资格与作者标签为 Mock，评论仓储为内存替身。 */
class DocumentCommentQueryTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private final CommentFixture fixture = new CommentFixture();
    private final CommentFixture.Store store = fixture.store;
    private Instant at = Instant.parse("2026-10-26T00:00:00Z");

    @Test
    void rootsAreNewestFirstWithLimitPlusOneAndExactContinuation() {
        List<CommentRow> roots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            roots.add(root(i % 2 == 0 ? AUTHOR : REPLIER, "根" + i));
        }
        store.seed(DOCUMENT, OTHER_VERSION, null, AUTHOR, "另一版本", at);
        var first = fixture.queries.roots(DOCUMENT, VERSION, Map.of("limit", List.of("2")));
        assertThat(first.items()).extracting(CommentViews.Item::body).containsExactly("根4", "根3");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.canCreate()).isTrue();
        // 先核对资格，再读有界页。
        assertThat(store.calls()).containsExactly("inspect", "roots");
        assertThat(store.pages()).containsExactly("roots:null:3");
        var second = fixture.queries.roots(DOCUMENT, VERSION, Map.of("limit", List.of("2"), "cursor",
                List.of(first.nextCursor())));
        assertThat(second.items()).extracting(CommentViews.Item::body).containsExactly("根2", "根1");
        var last = fixture.queries.roots(DOCUMENT, VERSION, Map.of("limit", List.of("2"), "cursor",
                List.of(second.nextCursor())));
        assertThat(last.items()).extracting(CommentViews.Item::body).containsExactly("根0");
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(store.pages()).containsExactly("roots:null:3", "roots:" + roots.get(3).id() + ":3",
                "roots:" + roots.get(1).id() + ":3");
        store.clearCalls();
        fixture.queries.roots(DOCUMENT, VERSION, Map.of());
        fixture.queries.roots(DOCUMENT, VERSION, Map.of("limit", List.of("100")));
        assertThat(store.pages()).containsExactly("roots:null:21", "roots:null:101");
        // 作者标签按页批量解析一次。
        assertThat(fixture.labelBatches.getFirst()).isEqualTo(Set.of(AUTHOR, REPLIER));
        var item = first.items().getFirst();
        assertThat(item.author()).isEqualTo(new CommentViews.Author(AUTHOR, "成员" + AUTHOR));
        assertThat(item.isOwn()).isTrue();
        assertThat(item.canDelete()).isTrue();
        assertThat(first.items().get(1).isOwn()).isFalse();
        assertThat(first.items().get(1).canDelete()).isFalse();
    }

    @Test
    void repliesAreOldestFirstWithAnIndependentCursorBoundToTheRoot() {
        var root = root(AUTHOR, "根");
        var other = root(AUTHOR, "另一根");
        for (int i = 0; i < 5; i++) {
            reply(root, REPLIER, "回复" + i);
        }
        reply(other, REPLIER, "别处回复");
        store.clearCalls();
        var first = fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of("limit", List.of("2")));
        assertThat(first.items()).extracting(CommentViews.Item::body).containsExactly("回复0", "回复1");
        assertThat(first.items()).extracting(CommentViews.Item::parentId).containsOnly(root.commentId());
        assertThat(first.canCreate()).isTrue();
        assertThat(store.calls()).containsExactly("inspect", "find", "replies");
        var second = fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of("limit", List.of("2"),
                "cursor", List.of(first.nextCursor())));
        assertThat(second.items()).extracting(CommentViews.Item::body).containsExactly("回复2", "回复3");
        var last = fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of("limit", List.of("2"),
                "cursor", List.of(second.nextCursor())));
        assertThat(last.items()).extracting(CommentViews.Item::body).containsExactly("回复4");
        assertThat(last.hasMore()).isFalse();
        // 根列表不嵌入回复，热门根也不触发回复读取。
        store.clearCalls();
        fixture.queries.roots(DOCUMENT, VERSION, Map.of());
        assertThat(store.calls()).doesNotContain("replies");
        // 回复游标只属于本根；用于其他根或根列表均为 400。
        assertStatus(() -> fixture.queries.replies(DOCUMENT, VERSION, other.commentId(), Map.of("cursor",
                List.of(first.nextCursor()))), 400);
        assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of("cursor", List.of(first.nextCursor()))), 400);
    }

    @Test
    void cursorsFromOtherVersionsKindsOrForgedFiltersAreRejected() {
        for (int i = 0; i < 3; i++) {
            root(AUTHOR, "根" + i);
        }
        var v1 = fixture.queries.roots(DOCUMENT, VERSION, Map.of("limit", List.of("1"))).nextCursor();
        assertStatus(() -> fixture.queries.roots(DOCUMENT, OTHER_VERSION, Map.of("cursor", List.of(v1))), 400);
        assertStatus(() -> fixture.queries.roots(DOCUMENT + 1, VERSION, Map.of("cursor", List.of(v1))), 400);
        var forgedKind = QueryCursor.encode("document-comment-replies",
                QueryParameters.filters("documentId", DOCUMENT, "versionId", VERSION), 5);
        var forgedFilter = QueryCursor.encode("document-comment-roots",
                QueryParameters.filters("documentId", DOCUMENT, "versionId", OTHER_VERSION), 5);
        var otherQuery = QueryCursor.encode("user-notifications", QueryParameters.filters("userId", AUTHOR), 5);
        for (String cursor : List.of(forgedKind, forgedFilter, otherQuery, "broken!", "e30")) {
            assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of("cursor", List.of(cursor))), 400);
        }
        for (var input : List.of(Map.of("limit", List.of("0")), Map.of("limit", List.of("101")),
                Map.of("limit", List.of("1", "2")), Map.of("limit", List.of("x")), Map.of("total", List.of("1")),
                Map.of("authorUserId", List.of("7")), Map.of("versionId", List.of("21")))) {
            assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, input), 400);
        }
        // 资格先于游标核对：不可读时外来游标同样是 404，不泄露游标是否有效。
        fixture.view = Access.HIDDEN;
        store.clearCalls();
        assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of("cursor", List.of(forgedFilter))), 404);
        assertThat(store.calls()).containsExactly("inspect");
    }

    @Test
    void tombstonesExposeOnlyStructureAndRepliesUnderADeletedRootStayReadable() throws Exception {
        var root = root(11, "删除前根正文");
        var live = reply(root, REPLIER, "仍在的回复");
        var gone = reply(root, 12, "删除前回复正文");
        store.tombstone(root.commentId(), at);
        store.tombstone(gone.commentId(), at);
        var page = fixture.queries.roots(DOCUMENT, VERSION, Map.of());
        var tombstone = page.items().getFirst();
        assertThat(tombstone.deleted()).isTrue();
        assertThat(tombstone.body()).isNull();
        assertThat(tombstone.author()).isNull();
        assertThat(tombstone.isOwn()).isFalse();
        assertThat(tombstone.canDelete()).isFalse();
        var replies = fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of());
        assertThat(replies.items()).extracting(CommentViews.Item::commentId).containsExactly(live.commentId(),
                gone.commentId());
        assertThat(replies.items().getFirst().body()).isEqualTo("仍在的回复");
        assertThat(replies.items().get(1).deleted()).isTrue();
        // 根已删除：已有回复可读，但不能追加新回复。
        assertThat(replies.canCreate()).isFalse();
        // 墓碑作者不再解析标签。
        assertThat(fixture.labelBatches).containsExactly(Set.of(), Set.of(REPLIER));
        String json = JSON.writeValueAsString(List.of(page, replies));
        assertThat(json).doesNotContain("删除前根正文").doesNotContain("删除前回复正文").doesNotContain("成员11")
                .doesNotContain("成员12");
        doReturn(Map.of()).when(fixture.identities).labels(any());
        assertStatus(() -> fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of()), 503);
    }

    @Test
    void readsRequireCurrentEligibilityAndExposeNothingWhenUnreadable() {
        var root = root(AUTHOR, "根");
        reply(root, REPLIER, "回复");
        for (var access : List.of(Access.MISSING, Access.HIDDEN)) {
            fixture.view = access;
            store.clearCalls();
            assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of()), 404);
            assertStatus(() -> fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of()), 404);
            assertStatus(() -> fixture.queries.detail(DOCUMENT, VERSION, root.commentId(), Map.of()), 404);
            assertThat(store.calls()).containsOnly("inspect");
        }
        assertThat(fixture.labelBatches).isEmpty();
        // 有 view 无 comment：可读但不可发表／回复。
        fixture.view = Access.READ;
        assertThat(fixture.queries.roots(DOCUMENT, VERSION, Map.of()).canCreate()).isFalse();
        assertThat(fixture.queries.replies(DOCUMENT, VERSION, root.commentId(), Map.of()).canCreate()).isFalse();
        assertThat(fixture.queries.detail(DOCUMENT, VERSION, root.commentId(), Map.of()).canReply()).isFalse();
        fixture.identityFailure = IdentityException.unauthorized("x");
        assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of()), 401);
        fixture.identityFailure = null;
        fixture.targetFailure = new IllegalStateException("acl down");
        assertStatus(() -> fixture.queries.roots(DOCUMENT, VERSION, Map.of()), 503);
    }

    @Test
    void fixedCommentTargetStaysOnItsVersionAndReportsReplyability() {
        var root = root(AUTHOR, "根");
        var reply = reply(root, REPLIER, "回复");
        fixture.as(REPLIER);
        var detail = fixture.queries.detail(DOCUMENT, VERSION, reply.commentId(), Map.of());
        assertThat(detail.item().commentId()).isEqualTo(reply.commentId());
        assertThat(detail.item().parentId()).isEqualTo(root.commentId());
        assertThat(detail.item().versionId()).isEqualTo(VERSION);
        assertThat(detail.item().isOwn()).isTrue();
        assertThat(detail.canReply()).isTrue();
        store.tombstone(root.commentId(), at);
        assertThat(fixture.queries.detail(DOCUMENT, VERSION, reply.commentId(), Map.of()).canReply()).isFalse();
        assertThat(fixture.queries.detail(DOCUMENT, VERSION, root.commentId(), Map.of()).item().deleted()).isTrue();
        // 不以其他版本或最新版本替代原回复。
        assertStatus(() -> fixture.queries.detail(DOCUMENT, OTHER_VERSION, reply.commentId(), Map.of()), 404);
        assertStatus(() -> fixture.queries.detail(DOCUMENT, VERSION, UUID.randomUUID(), Map.of()), 404);
        assertStatus(() -> fixture.queries.detail(DOCUMENT, VERSION, reply.commentId(), Map.of("limit", List.of("1"))), 400);
        // 只有根评论有回复页。
        assertStatus(() -> fixture.queries.replies(DOCUMENT, VERSION, reply.commentId(), Map.of()), 404);
    }

    @Test
    void inconsistentOrOversizedRowsAreUnavailableNotSilentlyFiltered() {
        var mapper = mock(DocumentCommentMapper.class);
        var service = new DocumentCommentQueryService(fixture.access, fixture.targets, mapper, fixture.identities,
                fixture.properties);
        var newer = new CommentRow(9, UUID.randomUUID(), DOCUMENT, VERSION, null, null, AUTHOR, "a", at, null);
        var older = new CommentRow(5, UUID.randomUUID(), DOCUMENT, VERSION, null, null, AUTHOR, "b", at, null);
        var foreign = new CommentRow(4, UUID.randomUUID(), DOCUMENT, OTHER_VERSION, null, null, AUTHOR, "c", at, null);
        var reply = new CommentRow(6, UUID.randomUUID(), DOCUMENT, VERSION, 5L, older.commentId(), AUTHOR, "d", at, null);
        for (var rows : List.of(List.of(newer, foreign), List.of(older, newer), List.of(newer, newer),
                List.of(newer, reply), List.of(newer, older, older, older))) {
            when(mapper.roots(eq(DOCUMENT), eq(VERSION), any(), anyInt())).thenReturn(rows);
            assertStatus(() -> service.roots(DOCUMENT, VERSION, Map.of("limit", List.of("2"))), 503);
        }
        when(mapper.roots(eq(DOCUMENT), eq(VERSION), any(), anyInt())).thenReturn(null);
        assertStatus(() -> service.roots(DOCUMENT, VERSION, Map.of()), 503);
        when(mapper.roots(eq(DOCUMENT), eq(VERSION), any(), anyInt())).thenThrow(new IllegalStateException("private sql"));
        assertThatThrownBy(() -> service.roots(DOCUMENT, VERSION, Map.of())).isInstanceOfSatisfying(ApiException.class,
                error -> {
                    assertThat(error.httpStatus()).isEqualTo(503);
                    assertThat(error.getMessage()).doesNotContain("sql");
                });
        when(mapper.find(DOCUMENT, VERSION, older.commentId())).thenReturn(older);
        var stray = new CommentRow(7, UUID.randomUUID(), DOCUMENT, VERSION, 3L, UUID.randomUUID(), AUTHOR, "e", at, null);
        var later = new CommentRow(8, UUID.randomUUID(), DOCUMENT, VERSION, 5L, older.commentId(), AUTHOR, "f", at, null);
        for (var rows : List.of(List.of(stray), List.of(later, reply))) {
            when(mapper.replies(eq(DOCUMENT), eq(VERSION), anyLong(), any(), anyInt())).thenReturn(rows);
            assertStatus(() -> service.replies(DOCUMENT, VERSION, older.commentId(), Map.of()), 503);
        }
    }

    @Test
    void readsUseBoundedReadOnlyTransactions() {
        var transactional = DocumentCommentQueryService.class.getAnnotation(Transactional.class);
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeout()).isEqualTo(10);
    }

    private CommentRow root(long author, String body) {
        at = at.plusSeconds(1);
        return store.seed(DOCUMENT, VERSION, null, author, body, at);
    }

    private CommentRow reply(CommentRow root, long author, String body) {
        at = at.plusSeconds(1);
        return store.seed(DOCUMENT, VERSION, root, author, body, at);
    }
}
