package com.zhishiku.kb.ai.chat;

/**
 * 无法安全确认配额时使用；调用方必须拒绝请求，不能退化为无限制。
 */
public class QuotaUnavailableException extends RuntimeException {

    public QuotaUnavailableException(String message) {
        super(message);
    }

    public QuotaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
