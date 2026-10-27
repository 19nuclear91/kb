package com.zhishiku.kb.app.comment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 评论的最小外部投影；能力字段只是本次读取的界面提示，不是后续授权凭证。 */
public final class CommentViews {
    private CommentViews() {
    }

    /**
     * 当前讨论所需的最小作者投影，不含用户名、部门、密级、角色或外部绑定。
     *
     * @param userId 作者内部标识
     * @param label 作者当前显示标签
     */
    @Schema(description = "评论作者最小投影")
    public record Author(
            @Schema(description = "作者内部标识") long userId,
            @Schema(description = "作者当前显示标签") String label) {
    }

    /**
     * 固定版本上的一条评论；墓碑只保留定位、删除状态与结构，不返回原正文或作者标签。
     *
     * @param commentId 评论标识
     * @param parentId 回复所属根评论；根评论为空
     * @param documentId 固定文档
     * @param versionId 固定版本
     * @param createdAt 创建时间
     * @param deleted 是否为删除墓碑
     * @param author 作者投影；墓碑为空
     * @param body 纯文本正文；墓碑为空
     * @param isOwn 是否当前用户本人的未删除评论
     * @param canDelete 当前用户可否删除，仅为界面提示
     */
    @Schema(description = "固定版本评论或删除墓碑")
    public record Item(
            @Schema(description = "评论标识") UUID commentId,
            @Schema(description = "回复所属根评论标识；根评论为空") UUID parentId,
            @Schema(description = "固定文档标识") long documentId,
            @Schema(description = "固定版本标识") long versionId,
            @Schema(description = "创建时间") Instant createdAt,
            @Schema(description = "是否为删除墓碑") boolean deleted,
            @Schema(description = "作者最小投影；墓碑为空") Author author,
            @Schema(description = "纯文本正文；墓碑为空") String body,
            @Schema(description = "是否当前用户本人的未删除评论") @JsonProperty("isOwn") boolean isOwn,
            @Schema(description = "当前用户可否删除，仅为界面提示") boolean canDelete) {
        public Item {
            Objects.requireNonNull(commentId, "commentId");
            if (deleted && (author != null || body != null || isOwn || canDelete)) {
                throw new IllegalArgumentException("Tombstone must not expose content");
            }
            if (!deleted && (author == null || body == null)) {
                throw new IllegalArgumentException("Live comment requires author and body");
            }
        }
    }

    /**
     * 根评论倒序有界页。
     *
     * @param items 本页根评论
     * @param hasMore 是否有下一页
     * @param nextCursor 续页游标，末页为空
     * @param canCreate 当前用户可否发表根评论，仅为界面提示
     */
    @Schema(description = "根评论游标页")
    public record Page(
            @Schema(description = "本页根评论") List<Item> items,
            @Schema(description = "是否有下一页") boolean hasMore,
            @Schema(description = "续页游标，末页为空") String nextCursor,
            @Schema(description = "当前用户可否发表根评论，仅为界面提示") boolean canCreate) {
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * 单个根评论下的回复正序有界页。
     *
     * @param items 本页回复
     * @param hasMore 是否有下一页
     * @param nextCursor 续页游标，末页为空
     * @param canCreate 当前用户可否回复该根评论，根已删除时为假
     */
    @Schema(description = "回复游标页")
    public record Replies(
            @Schema(description = "本页回复") List<Item> items,
            @Schema(description = "是否有下一页") boolean hasMore,
            @Schema(description = "续页游标，末页为空") String nextCursor,
            @Schema(description = "当前用户可否回复该根评论，仅为界面提示") boolean canCreate) {
        public Replies {
            items = List.copyOf(items);
        }
    }

    /**
     * 固定评论目标详情，用于回复通知定位；仍按版本当前权限读取。
     *
     * @param item 目标评论或墓碑
     * @param canReply 当前用户可否回复其根评论，仅为界面提示
     */
    @Schema(description = "固定评论目标详情")
    public record Detail(
            @Schema(description = "目标评论或墓碑") Item item,
            @Schema(description = "当前用户可否回复其根评论，仅为界面提示") boolean canReply) {
    }

    /**
     * 创建回执；不固化正文，需要正文时通过受保护读取取得。
     *
     * @param commentId 评论标识
     * @param parentId 回复所属根评论；根评论为空
     * @param documentId 固定文档
     * @param versionId 固定版本
     * @param createdAt 服务端创建时间
     * @param deleted 原评论是否已由本人删除
     * @param replayed 是否为匹配请求标识的重放
     */
    @Schema(description = "评论创建最小回执")
    public record Receipt(
            @Schema(description = "评论标识") UUID commentId,
            @Schema(description = "回复所属根评论标识；根评论为空") UUID parentId,
            @Schema(description = "固定文档标识") long documentId,
            @Schema(description = "固定版本标识") long versionId,
            @Schema(description = "服务端创建时间") Instant createdAt,
            @Schema(description = "原评论是否已删除") boolean deleted,
            @Schema(description = "是否为匹配请求标识的重放") boolean replayed) {
    }
}
