package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** 个人收藏的最小外部投影。 */
public final class DocumentFavoriteViews {
    private DocumentFavoriteViews() {
    }

    /**
     * 当前可查看的收藏文档。
     *
     * @param documentId 稳定文档标识
     * @param title 当前文档标题
     * @param spaceId 当前所属空间
     * @param securityLevel 当前文档密级
     * @param currentVersionId 当前发布版本，未发布时为空
     * @param currentVersionNo 当前发布版本号，未发布时为空
     * @param favoritedAt 原收藏创建时间
     */
    @Schema(description = "当前可查看的个人收藏")
    public record Item(
            @Schema(description = "文档标识") long documentId,
            @Schema(description = "当前标题") String title,
            @Schema(description = "当前空间标识") long spaceId,
            @Schema(description = "当前文档密级") short securityLevel,
            @Schema(description = "当前发布版本标识，未发布时为空") Long currentVersionId,
            @Schema(description = "当前发布版本号，未发布时为空") Integer currentVersionNo,
            @Schema(description = "收藏创建时间") Instant favoritedAt) {
    }

    /**
     * 本人收藏状态，不能作为文档授权凭据。
     *
     * @param documentId 文档标识
     * @param favorited 是否已收藏
     */
    @Schema(description = "本人文档收藏状态")
    public record State(
            @Schema(description = "文档标识") long documentId,
            @Schema(description = "是否已收藏") boolean favorited) {
    }

    /**
     * 仅含当前可查看目标的批量状态。
     *
     * @param items 按请求顺序排列的可见目标状态
     */
    @Schema(description = "本人收藏状态批次")
    public record States(@Schema(description = "可见目标状态") List<State> items) {
        public States {
            items = List.copyOf(items);
        }
    }

    /**
     * 按收藏创建位置倒序的可见页。
     *
     * @param items 本页可见收藏
     * @param hasMore 本次查询是否有下一页
     * @param nextCursor 续页游标，末页为空
     */
    @Schema(description = "个人收藏游标页")
    public record Page(
            @Schema(description = "可见收藏") List<Item> items,
            @Schema(description = "是否有下一页") boolean hasMore,
            @Schema(description = "续页游标，末页为空") String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }
    }
}
