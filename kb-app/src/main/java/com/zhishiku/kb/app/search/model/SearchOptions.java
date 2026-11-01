package com.zhishiku.kb.app.search.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 搜索专用目录的输入与最小结果投影。 */
public final class SearchOptions {
    private SearchOptions() {
    }

    /**
     * 分类分层读取条件。
     * @param spaceId 空间 ID
     * @param parentId 父节点，null 为根层
     * @param limit 页大小
     * @param cursor 搜索用途游标
     */
    @Schema(description = "搜索分类候选请求")
    public record CategoryQuery(@Schema(description = "空间 ID") long spaceId,
                                @Schema(description = "父分类，可空") Long parentId,
                                @Schema(description = "页大小，1–100，默认 20") int limit,
                                @Schema(description = "上一页游标") String cursor) {
        public CategoryQuery {
            if (spaceId <= 0 || (parentId != null && parentId <= 0)) {
                throw invalid();
            }
            page(limit, cursor);
        }
    }

    /**
     * 全局标签读取条件。
     * @param nameQuery 标签名称字面查询，null 表示不限
     * @param limit 页大小
     * @param cursor 搜索用途游标
     */
    @Schema(description = "搜索标签候选请求")
    public record TagQuery(@Schema(description = "名称字面包含，最多 64 个字符") String nameQuery,
                           @Schema(description = "页大小，1–100，默认 20") int limit,
                           @Schema(description = "上一页游标") String cursor) {
        public TagQuery {
            page(limit, cursor);
            if (nameQuery != null) {
                int start = 0;
                int end = nameQuery.length();
                while (start < end && whitespace(nameQuery.codePointAt(start))) {
                    start += Character.charCount(nameQuery.codePointAt(start));
                }
                while (end > start && whitespace(nameQuery.codePointBefore(end))) {
                    end -= Character.charCount(nameQuery.codePointBefore(end));
                }
                nameQuery = nameQuery.substring(start, end);
                if (nameQuery.isEmpty()) {
                    nameQuery = null;
                } else {
                    SearchInput.validate(nameQuery, 1, 1, 64);
                }
            }
        }
    }

    /** @param id 分类 ID @param name 当前名称 */
    @Schema(description = "分类路径节点")
    public record PathNode(@Schema(description = "分类 ID") long id,
                            @Schema(description = "名称") String name) {
    }

    /**
     * 分类候选。
     * @param id 分类 ID
     * @param parentId 父分类 ID
     * @param name 当前名称
     * @param path 从根到本节点的路径
     * @param sortOrder 同层排序
     */
    @Schema(description = "搜索分类候选")
    public record Category(@Schema(description = "分类 ID") long id,
                            @Schema(description = "父分类，可空") Long parentId,
                            @Schema(description = "名称") String name,
                            @Schema(description = "当前路径") List<PathNode> path,
                            @Schema(description = "同层排序") int sortOrder) {
        public Category {
            path = List.copyOf(path);
        }
    }

    /** @param id 标签 ID @param name 当前名称 @param enabled 是否启用 */
    @Schema(description = "搜索标签候选")
    public record Tag(@Schema(description = "标签 ID") long id,
                       @Schema(description = "名称") String name,
                       @Schema(description = "启用状态，停用关联仍可筛选") boolean enabled) {
    }

    /**
     * 分类候选页。
     * @param items 本页条目
     * @param hasMore 是否还有下一页
     * @param nextCursor 下页游标
     * @param treeVersion 分类树版本，无可见上下文时为零
     */
    @Schema(description = "搜索分类候选页")
    public record CategoryPage(@Schema(description = "候选") List<Category> items,
                                @Schema(description = "还有下一页") boolean hasMore,
                                @Schema(description = "下页游标，可空") String nextCursor,
                                @Schema(description = "树版本") long treeVersion) {
        public CategoryPage {
            items = List.copyOf(items);
        }
    }

    /** @param items 本页标签 @param hasMore 是否还有下一页 @param nextCursor 下页游标 */
    @Schema(description = "搜索标签候选页")
    public record TagPage(@Schema(description = "候选") List<Tag> items,
                           @Schema(description = "还有下一页") boolean hasMore,
                           @Schema(description = "下页游标，可空") String nextCursor) {
        public TagPage {
            items = List.copyOf(items);
        }
    }

    /** @param id 分类 ID @param parentId 父分类 @param name 名称 @param sortOrder 排序 */
    public record CategoryRow(long id, Long parentId, String name, int sortOrder) {
    }

    /** @param leafId 目标节点 @param id 路径节点 @param parentId 父节点 @param name 名称 @param depth 从目标向上的深度 */
    public record PathRow(long leafId, long id, Long parentId, String name, int depth) {
    }

    private static void page(int limit, String cursor) {
        if (limit < 1 || limit > 100 || (cursor != null && !cursor.matches("[A-Za-z0-9_-]{1,4096}"))) {
            throw invalid();
        }
    }

    private static boolean whitespace(int codepoint) {
        return Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint);
    }

    private static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
    }
}
