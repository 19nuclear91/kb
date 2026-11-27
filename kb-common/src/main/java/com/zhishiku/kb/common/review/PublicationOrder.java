package com.zhishiku.kb.common.review;

import java.time.Instant;
import java.util.Objects;

/**
 * 订阅发布协议的事务顺序，不是可跨事务重用的发布许可。
 *
 * @param position 数据库事务性递增位置，必须大于零
 * @param businessTime 同次取得的非递减数据库时间
 */
public record PublicationOrder(
        long position,
        Instant businessTime
) {
    public PublicationOrder {
        if (position <= 0) {
            throw new IllegalArgumentException("Invalid publication order");
        }
        Objects.requireNonNull(businessTime, "businessTime");
    }
}
