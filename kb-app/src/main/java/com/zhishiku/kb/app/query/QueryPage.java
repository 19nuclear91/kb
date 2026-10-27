package com.zhishiku.kb.app.query;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.function.Function;

/**
 * 不暴露全局总数的查询页。
 *
 * @param items      当前页条目
 * @param hasMore    是否还有下一页
 * @param nextCursor 下一页位置，为空表示结束
 * @param <T>        条目类型
 */
@Schema(description = "有界查询页")
public record QueryPage<T>(
        @Schema(description = "当前页条目") List<T> items,
        @Schema(description = "是否还有下一页") boolean hasMore,
        @Schema(description = "下一页游标，结束时为空") String nextCursor) {
    public QueryPage {
        items = List.copyOf(items);
    }

    public static <T> QueryPage<T> slice(List<T> rows, int limit, Function<T, String> cursor) {
        boolean more = rows.size() > limit;
        List<T> items = rows.subList(0, Math.min(rows.size(), limit));
        return new QueryPage<>(items, more, more ? cursor.apply(items.get(items.size() - 1)) : null);
    }
}
