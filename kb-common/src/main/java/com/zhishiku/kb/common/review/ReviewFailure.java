package com.zhishiku.kb.common.review;

import java.util.Objects;

/** 仅携带固定协议错误；HTTP 层不能使用底层异常消息判定审核状态。 */
public final class ReviewFailure extends RuntimeException {
    private final ReviewError error;

    public ReviewFailure(ReviewError error) {
        super(Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    public ReviewError error() {
        return error;
    }
}
