package com.zhishiku.kb.ai.conversation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 提交响应前只返回固定错误消息，框架异常不携带敏感输入。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = MultiTurnChatController.class)
public class ConversationExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException error) {
        if (error instanceof InputBudgetExceededException) {
            return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                    .body(Map.of("code", InputBudgetExceededException.CODE, "message", InputBudgetExceededException.MESSAGE));
        }
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store").body(Map.of("message"
                , "会话请求未被接受"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> invalid(Exception error) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(Map.of("message", "会话请求参数无效"));
    }
}

