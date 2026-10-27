package com.zhishiku.kb.app.space.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 仅固定错误码进入响应，不回显请求正文或底层异常。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SpacePublicationPolicyController.class)
public class SpacePublicationPolicyAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Map<String, String>>> failure(Exception error) {
        int status = 503;
        if (error instanceof QueryException query) status = query.status();
        else if (error instanceof SaTokenException) status = 401;
        else if (error instanceof IdentityException identity) status = identity.httpStatus();
        else if (error instanceof ApiException api) status = api.httpStatus();
        else if (error instanceof HttpMediaTypeNotSupportedException) status = 415;
        String code = switch (status) {
            case 400, 415 -> "INVALID_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 409 -> "STATE_CONFLICT";
            default -> "SERVICE_UNAVAILABLE";
        };
        if (status >= 500) status = 503;
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new R<>(R.FAILURE, "审核策略请求未完成", Map.of("errorCode", code)));
    }
}
