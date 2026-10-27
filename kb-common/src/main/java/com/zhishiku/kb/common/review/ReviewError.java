package com.zhishiku.kb.common.review;

/** 可安全返回的固定审核错误，不承载正文、原因或依赖异常。 */
public enum ReviewError {
    INVALID_REQUEST(400), UNAUTHENTICATED(401), FORBIDDEN(403), NOT_FOUND(404),
    STATE_CONFLICT(409), NO_ELIGIBLE_REVIEWER(409), EMBEDDING_MAINTENANCE(409), INDEX_REFRESH_REQUIRED(409),
    SERVICE_UNAVAILABLE(503);

    private final int status;

    ReviewError(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
