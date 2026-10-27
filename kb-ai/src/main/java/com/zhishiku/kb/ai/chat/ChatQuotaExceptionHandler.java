package com.zhishiku.kb.ai.chat;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将请求体校验错误转换为统一响应；SSE 已建立后的配额错误由事件表达。
 */
@RestControllerAdvice
public class ChatQuotaExceptionHandler {

    @ExceptionHandler(ChatQuotaAuditService.QuotaExceededException.class)
    public ChatEvent quotaExceeded(ChatQuotaAuditService.QuotaExceededException ignored) {
        return new ChatEvent.Error("请求过于频繁，请稍后重试。");
    }
}
