package com.zhishiku.kb.app.document.metadata.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.zhishiku.kb.app.document.metadata.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MetadataAdvice {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        int status;
        MetadataException.Reason reason;
        if (error instanceof MetadataException metadata) {
            reason = metadata.reason();
            status = reason.status();
        } else {
            status = error instanceof ApiException api ? api.httpStatus()
                    : error instanceof QueryException query ? query.status()
                    : error instanceof SaTokenException ? 401
                    : error instanceof ServletRequestBindingException ? 400 : 503;
            reason = switch (status) {
                case 400 -> MetadataException.Reason.INVALID_INPUT;
                case 401, 403 -> MetadataException.Reason.FORBIDDEN;
                case 404 -> MetadataException.Reason.NOT_FOUND;
                default -> MetadataException.Reason.UNAVAILABLE;
            };
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, new MetadataException(reason).getMessage(), null, reason.name()));
    }
}
