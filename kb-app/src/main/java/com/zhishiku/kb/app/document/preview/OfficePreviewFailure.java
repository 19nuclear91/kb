package com.zhishiku.kb.app.document.preview;

/** 只携带固定安全错误，禁止传入上游响应或认证凭据。 */
public final class OfficePreviewFailure extends RuntimeException {
    private final int status;

    public OfficePreviewFailure(int status) {
        super(switch (status) {
            case 400 -> "预览请求无效";
            case 401 -> "登录状态已失效";
            case 403 -> "必须先完成密码修改";
            case 404 -> "文档或预览会话不可访问";
            case 415 -> "当前格式不支持在线预览";
            case 429 -> "预览会话数量已达上限，请稍后重试";
            default -> "Office 预览暂不可用";
        });
        this.status = status;
    }

    public int status() {
        return status;
    }
}
