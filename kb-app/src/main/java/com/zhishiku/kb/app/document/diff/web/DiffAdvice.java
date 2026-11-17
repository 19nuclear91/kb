package com.zhishiku.kb.app.document.diff.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.document.diff.service.DiffException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.zhishiku.kb.app.document.diff.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiffAdvice {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        int status = 503;
        String reason = "UNAVAILABLE";
        if (error instanceof DiffException diff) {
            status = diff.status();
            reason = diff.reasonCode();
        } else if (error instanceof IdentityException identity
                && identity.errorCode() == IdentityErrorCode.PASSWORD_CHANGE_REQUIRED) {
            status = 403;
            reason = "PASSWORD_CHANGE_REQUIRED";
        } else if (error instanceof SaTokenException) {
            status = 401;
            reason = "UNAUTHORIZED";
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, reason, null, reason));
    }
}
