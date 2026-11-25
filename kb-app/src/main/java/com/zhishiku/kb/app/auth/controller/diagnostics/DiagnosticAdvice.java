package com.zhishiku.kb.app.auth.controller.diagnostics;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.auth.service.diagnostics.DiagnosticException;
import io.swagger.v3.oas.annotations.media.Schema;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice(assignableTypes = DiagnosticController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiagnosticAdvice {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "诊断固定失败封装，不含输入或依赖异常")
    public record Failure(
            @Schema(description = "固定失败业务码 500")
            int code,
            @Schema(description = "固定安全中文提示")
            String message,
            @Schema(description = "失败数据恒为 null", nullable = true)
            Void data,
            @Schema(description = "INVALID_INPUT、DIAGNOSTIC_LIMIT_EXCEEDED、PAYLOAD_TOO_LARGE、UNAUTHORIZED、FORBIDDEN、NOT_FOUND 或 DIAGNOSTIC_UNAVAILABLE")
            String reasonCode
    ) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        return project(error);
    }

    public static ResponseEntity<Failure> project(Exception error) {
        DiagnosticException failure;
        if (error instanceof DiagnosticException diagnostic) {
            failure = diagnostic;
        } else if (error instanceof SaTokenException || error instanceof IdentityException identity && identity.httpStatus() == 401) {
            failure = new DiagnosticException(DiagnosticException.Reason.UNAUTHORIZED);
        } else if (error instanceof IdentityException) {
            failure = new DiagnosticException(DiagnosticException.Reason.FORBIDDEN);
        } else if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            failure = new DiagnosticException(DiagnosticException.Reason.NOT_FOUND);
        } else if (error instanceof HttpMessageNotReadableException || error instanceof HttpMediaTypeNotSupportedException
                || error instanceof HttpRequestMethodNotSupportedException) {
            failure = new DiagnosticException(DiagnosticException.Reason.INVALID_INPUT);
        } else {
            failure = new DiagnosticException(DiagnosticException.Reason.DIAGNOSTIC_UNAVAILABLE);
        }
        return ResponseEntity.status(failure.reason().status()).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, failure.getMessage(), null, failure.reason().name()));
    }
}
