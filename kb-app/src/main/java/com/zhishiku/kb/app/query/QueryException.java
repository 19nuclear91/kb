package com.zhishiku.kb.app.query;

/**
 * 查询错误只保存固定类别，不携带用户值或底层异常。
 */
public final class QueryException extends RuntimeException {
    private final int status;

    public QueryException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static QueryException invalid() {
        return new QueryException(400, "查询参数无效");
    }

    public static QueryException forbidden() {
        return new QueryException(403, "查询访问被拒绝");
    }

    public static QueryException missing() {
        return new QueryException(404, "目标不存在或不可管理");
    }
}
