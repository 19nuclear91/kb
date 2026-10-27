package com.zhishiku.kb.app.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.comment.model.CommentCommand;
import com.zhishiku.kb.app.comment.model.CommentCommandRow;
import com.zhishiku.kb.app.comment.model.CommentRow;
import com.zhishiku.kb.app.comment.model.CommentText;
import com.zhishiku.kb.app.comment.model.CommentViews;
import com.zhishiku.kb.app.comment.model.OwnComment;
import com.zhishiku.kb.app.comment.web.CommentInput;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 正文规范化、结构／墓碑模型、请求绑定与严格输入；纯内存，不连接数据库。 */
class CommentModelTest {
    private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPLY = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Instant AT = Instant.parse("2026-10-26T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void normalizationOnlyStripsOuterWhitespaceAndCountsCodePoints() {
        assertThat(CommentText.normalize("  \n\t第一行\r\n第二行\n  ")).isEqualTo("第一行\r\n第二行");
        assertThat(CommentText.normalize("😀")).isEqualTo("😀");
        String max = "😀".repeat(CommentText.MAX_CODE_POINTS);
        assertThat(max.length()).isEqualTo(2 * CommentText.MAX_CODE_POINTS);
        assertThat(CommentText.normalize(" " + max + " ")).isEqualTo(max);
        assertThat(CommentText.normalize("a".repeat(CommentText.MAX_CODE_POINTS))).hasSize(CommentText.MAX_CODE_POINTS);
        // 不做 Unicode 兼容折叠：组合字符与预组合字符保持原样。
        assertThat(CommentText.normalize("e\u0301")).isEqualTo("e\u0301").isNotEqualTo("\u00e9");
        // 标记文本只按纯文本保存。
        String markup = "<script>alert(1)</script> ![x](https://example.invalid/a.png) **粗体**";
        assertThat(CommentText.normalize(markup)).isEqualTo(markup);
    }

    @Test
    void emptyOverLongNulAndLoneSurrogatesAreRejectedWithoutTruncation() {
        List<String> invalid = new ArrayList<>(Arrays.asList(null, "", "   ", "\n\t ", "a\u0000b", "\u0000",
                "x\uD800", "\uDC00y", "\uDC00\uD800", "a".repeat(CommentText.MAX_CODE_POINTS + 1),
                "😀".repeat(CommentText.MAX_CODE_POINTS) + "a"));
        for (String value : invalid) {
            assertThatThrownBy(() -> CommentText.normalize(value)).as(String.valueOf(value == null ? null : value.length()))
                    .isInstanceOfSatisfying(QueryException.class, error -> assertThat(error.status()).isEqualTo(400));
        }
    }

    @Test
    void rowsKeepSingleLevelStructureAndTombstoneInvariant() {
        var root = new CommentRow(1, ROOT, 10, 20, null, null, 7, "正文", AT, null);
        var reply = new CommentRow(2, REPLY, 10, 20, 1L, ROOT, 8, "回复", AT, null);
        var tombstone = new CommentRow(3, UUID.randomUUID(), 10, 20, null, null, 7, null, AT, AT);
        assertThat(root.root()).isTrue();
        assertThat(root.deleted()).isFalse();
        assertThat(reply.root()).isFalse();
        assertThat(tombstone.deleted()).isTrue();
        List<Executable> invalid = List.of(
                () -> new CommentRow(0, ROOT, 10, 20, null, null, 7, "x", AT, null),
                () -> new CommentRow(1, ROOT, 0, 20, null, null, 7, "x", AT, null),
                () -> new CommentRow(1, ROOT, 10, 0, null, null, 7, "x", AT, null),
                () -> new CommentRow(1, ROOT, 10, 20, null, null, 0, "x", AT, null),
                // 根位置与根身份必须同时出现。
                () -> new CommentRow(2, REPLY, 10, 20, 1L, null, 8, "x", AT, null),
                () -> new CommentRow(2, REPLY, 10, 20, null, ROOT, 8, "x", AT, null),
                // 回复不能指向自身或更晚创建的位置。
                () -> new CommentRow(2, REPLY, 10, 20, 1L, REPLY, 8, "x", AT, null),
                () -> new CommentRow(2, REPLY, 10, 20, 2L, ROOT, 8, "x", AT, null),
                () -> new CommentRow(2, REPLY, 10, 20, 3L, ROOT, 8, "x", AT, null),
                // 墓碑与正文互斥。
                () -> new CommentRow(1, ROOT, 10, 20, null, null, 7, null, AT, null),
                () -> new CommentRow(1, ROOT, 10, 20, null, null, 7, "x", AT, AT));
        for (var construct : invalid) {
            assertThrows(IllegalArgumentException.class, construct);
        }
        assertThrows(NullPointerException.class, () -> new CommentRow(1, null, 10, 20, null, null, 7, "x", AT, null));
        assertThrows(NullPointerException.class, () -> new CommentRow(1, ROOT, 10, 20, null, null, 7, "x", null, null));
        assertThat(reply.toString()).doesNotContain("回复").doesNotContain(ROOT.toString());
    }

    @Test
    void ownCommentProjectionCarriesNoBodyOrAuthorLabel() {
        assertThat(new OwnComment(2, REPLY, 10, 20, ROOT, true).deleted()).isTrue();
        assertThrows(IllegalArgumentException.class, () -> new OwnComment(0, REPLY, 10, 20, null, false));
        assertThrows(IllegalArgumentException.class, () -> new OwnComment(2, REPLY, 10, 20, REPLY, false));
        assertThrows(NullPointerException.class, () -> new OwnComment(2, null, 10, 20, null, false));
        assertThat(Arrays.stream(OwnComment.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("body", "label", "authorUserId");
    }

    @Test
    void commandBindsDomainActorVersionParentAndNormalizedBodyDigestOnly() {
        var command = CommentCommand.create("req-1", 7, 10, 20, ROOT, "正文");
        assertThat(command.domain()).isEqualTo("COMMENT_CREATE");
        assertThat(command.bodyDigest()).isEqualTo(ReviewCommandBinding.digest("COMMENT_CREATE", "正文"));
        assertThat(command.toString()).doesNotContain("正文");
        assertThat(Arrays.stream(CommentCommand.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("body");
        assertThat(command.matches(saved(command, ROOT, command.bodyDigest(), 7, 20))).isTrue();
        for (var other : List.of(saved(command, null, command.bodyDigest(), 7, 20),
                saved(command, REPLY, command.bodyDigest(), 7, 20),
                saved(command, ROOT, ReviewCommandBinding.digest("COMMENT_CREATE", "正文 "), 7, 20),
                saved(command, ROOT, command.bodyDigest(), 8, 20),
                saved(command, ROOT, command.bodyDigest(), 7, 21),
                new CommentCommandRow("REVIEW_SUBMIT", "req-1", 7, 10, 20, ROOT, command.bodyDigest(), null),
                new CommentCommandRow("COMMENT_CREATE", "req-2", 7, 10, 20, ROOT, command.bodyDigest(), null))) {
            assertThat(command.matches(other)).as(other.toString()).isFalse();
        }
        assertThat(command.matches(null)).isFalse();
        // 摘要只覆盖规范化正文：首尾空白不同的原文得到同一绑定。
        assertThat(CommentCommand.create("req-1", 7, 10, 20, ROOT, CommentText.normalize("  正文\n")))
                .isEqualTo(command);
    }

    @Test
    void requestIdIsOneToOneHundredTwentyEightNonControlCodePoints() {
        for (String valid : List.of("r", "😀".repeat(128), "请求-1 a")) {
            assertThat(CommentCommand.create(valid, 7, 10, 20, null, "x").requestId()).isEqualTo(valid);
        }
        for (String invalid : Arrays.asList(null, "", "  ", "a".repeat(129), "😀".repeat(129), "a\nb", "a\u0000",
                "a\u007f", "x\uD800")) {
            assertThrows(IllegalArgumentException.class, () -> CommentCommand.create(invalid, 7, 10, 20, null, "x"));
        }
        assertThrows(IllegalArgumentException.class, () -> new CommentCommand("r", 0, 10, 20, null, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CommentCommand("r", 7, 10, 0, null, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CommentCommand("r", 7, 10, 20, null, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CommentCommand("r", 7, 10, 20, null, null));
    }

    @Test
    void viewsSerializeIsOwnAndTombstonesNeverCarryAuthorOrBody() throws Exception {
        var author = new CommentViews.Author(9007199254740993L, "成员");
        var live = new CommentViews.Item(REPLY, ROOT, 10, 20, AT, false, author, "回复", true, true);
        var tombstone = new CommentViews.Item(ROOT, null, 10, 20, AT, true, null, null, false, false);
        List<Executable> invalid = List.of(
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, true, author, null, false, false),
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, true, null, "原文", false, false),
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, true, null, null, true, false),
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, true, null, null, false, true),
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, false, null, "x", false, false),
                () -> new CommentViews.Item(ROOT, null, 10, 20, AT, false, author, null, false, false));
        for (var construct : invalid) {
            assertThrows(IllegalArgumentException.class, construct);
        }
        JsonNode node = JSON.valueToTree(live);
        assertThat(node.has("own")).isFalse();
        assertThat(node.get("isOwn").booleanValue()).isTrue();
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("commentId", "parentId", "documentId", "versionId",
                "createdAt", "deleted", "author", "body", "isOwn", "canDelete");
        assertThat(fieldNames(node.get("author"))).containsExactlyInAnyOrder("userId", "label");
        JsonNode deleted = JSON.valueToTree(tombstone);
        assertThat(deleted.get("body").isNull()).isTrue();
        assertThat(deleted.get("author").isNull()).isTrue();
        assertThat(deleted.get("isOwn").booleanValue()).isFalse();
        JsonNode receipt = JSON.valueToTree(new CommentViews.Receipt(REPLY, ROOT, 10, 20, AT, true, true));
        assertThat(fieldNames(receipt)).containsExactlyInAnyOrder("commentId", "parentId", "documentId", "versionId",
                "createdAt", "deleted", "replayed");
        JsonNode page = JSON.valueToTree(new CommentViews.Page(List.of(live), true, "c", false));
        assertThat(fieldNames(page)).containsExactlyInAnyOrder("items", "hasMore", "nextCursor", "canCreate");
    }

    @Test
    void strictCreateInputAcceptsOnlyBodyAndOptionalRootUuid() {
        var root = CommentInput.create(input("{\"body\":\"第一行\\n第二行\"}"));
        assertThat(root.body()).isEqualTo("第一行\n第二行");
        assertThat(root.parentId()).isNull();
        var reply = CommentInput.create(input("{\"parentId\":\"" + ROOT + "\",\"body\":\" <b>x</b> \"}"));
        assertThat(reply.parentId()).isEqualTo(ROOT);
        assertThat(reply.body()).isEqualTo(" <b>x</b> ");
        assertThat(CommentInput.create(input("{\"body\":\"x\",\"parentId\":\"" + ROOT.toString().toUpperCase() + "\"}"))
                .parentId()).isEqualTo(ROOT);
        // 4000 个补充平面字符以 \\uXXXX 转义仍在请求上限内，长度由正文规则判定。
        String escaped = "\\ud83d\\ude00".repeat(CommentText.MAX_CODE_POINTS);
        assertThat(CommentInput.create(input("{\"body\":\"" + escaped + "\"}")).body())
                .isEqualTo("😀".repeat(CommentText.MAX_CODE_POINTS));
        List<String> invalid = List.of("", " ", "{}", "[]", "null", "\"x\"", "{", "{\"body\":\"x\"} {}",
                "{\"body\":\"x\"}x", "{\"body\":null}", "{\"body\":1}", "{\"body\":[\"x\"]}", "{\"body\":{\"t\":\"x\"}}",
                "{\"body\":\"x\",\"body\":\"y\"}", "{\"body\":\"x\",\"parentId\":null}",
                "{\"body\":\"x\",\"parentId\":1}", "{\"body\":\"x\",\"parentId\":\"1-2-3-4-5\"}",
                "{\"body\":\"x\",\"parentId\":\"" + ROOT + "x\"}", "{\"body\":\"x\",\"authorUserId\":7}",
                "{\"body\":\"x\",\"author\":{\"userId\":7}}", "{\"body\":\"x\",\"createdAt\":\"2026-01-01T00:00:00Z\"}",
                "{\"body\":\"x\",\"deleted\":true}", "{\"body\":\"x\",\"commentId\":\"" + ROOT + "\"}",
                "{\"body\":\"x\",\"documentId\":1}", "{\"body\":\"x\",\"versionId\":1}", "{\"body\":\"x\",\"requestId\":\"r\"}",
                "{\"text\":\"x\"}", "{\"body\":\"" + "a".repeat(64 * 1024) + "\"}");
        for (String body : invalid) {
            assertThatThrownBy(() -> CommentInput.create(input(body))).as(body.length() > 80 ? "oversized" : body)
                    .isInstanceOfSatisfying(QueryException.class, error -> assertThat(error.status()).isEqualTo(400));
        }
    }

    @Test
    void pathCommentIdentifiersMustBeCanonicalUuids() {
        assertThat(CommentInput.commentId(ROOT.toString())).isEqualTo(ROOT);
        for (String invalid : Arrays.asList(null, "", "1-2-3-4-5", ROOT.toString().replace("-", ""), ROOT + "0",
                "g" + ROOT.toString().substring(1), "{" + ROOT + "}")) {
            assertThatThrownBy(() -> CommentInput.commentId(invalid)).isInstanceOf(QueryException.class);
        }
    }

    private static CommentCommandRow saved(CommentCommand command, UUID parent, String digest, long actor, long version) {
        return new CommentCommandRow("COMMENT_CREATE", command.requestId(), actor, command.documentId(), version, parent,
                digest, null);
    }

    private static ByteArrayInputStream input(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
