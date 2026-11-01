package com.zhishiku.kb.app.document.metadata.service;

public final class MetadataException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(400, "请求参数无效"), PAYLOAD_TOO_LARGE(413, "请求体过大"),
        NOT_FOUND(404, "资源不存在或不可访问"), FORBIDDEN(403, "当前无操作资格"),
        METADATA_VERSION_CONFLICT(409, "文档资料已变化，请重新读取"),
        TREE_VERSION_CONFLICT(409, "分类目录已变化，请重新读取"),
        TAG_VERSION_CONFLICT(409, "标签已变化，请重新读取"),
        CATEGORY_UNAVAILABLE(409, "分类不可选，请重新选择"),
        TAG_UNAVAILABLE(409, "标签不可选，请重新选择"),
        NAME_CONFLICT(409, "名称已被占用"), REFERENCE_CONFLICT(409, "存在保留引用，不能删除"),
        REQUEST_MISMATCH(409, "请求标识已用于不同操作"), NO_CHANGE(409, "资料没有变化"),
        TREE_STRUCTURE_CONFLICT(409, "分类层级无效"), SPACE_ARCHIVED(409, "空间已归档"),
        UNAVAILABLE(503, "服务暂不可用，请稍后核对");

        private final int status;
        private final String message;

        Reason(int status, String message) {
            this.status = status;
            this.message = message;
        }

        public int status() {
            return status;
        }
    }

    private final Reason reason;

    public MetadataException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
