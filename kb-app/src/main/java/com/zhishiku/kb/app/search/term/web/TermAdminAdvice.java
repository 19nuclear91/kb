package com.zhishiku.kb.app.search.term.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.search.term.TermException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

/** 固定管理失败，不记录异常链，不回显输入、词条或凭据。 */
@RestControllerAdvice(assignableTypes = TermAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TermAdminAdvice {
    /**
     * 固定词典失败投影。
     * @param code 统一失败业务码
     * @param message 固定安全说明
     * @param data 失败时恒为 null
     * @param reasonCode 固定失败类别
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "固定词典失败投影")
    public record Failure(@Schema(description = "统一失败业务码") int code,
                          @Schema(description = "固定安全说明") String message,
                          @Schema(description = "失败时恒为 null") Object data,
                          @Schema(description = "固定失败类别") String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        return project(error);
    }

    public static ResponseEntity<Failure> project(Exception error) {
        if (error instanceof TermException term) {
            return response(term.reason().status(), term.getMessage(), term.reason().name());
        }
        int status = status(error);
        if (!Set.of(400, 401, 403, 404, 405, 406, 409, 413, 415, 500, 503).contains(status)) {
            status = 500;
        }
        String reason = switch (status) {
            case 400 -> "INVALID_INPUT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406, 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 409 -> "STATE_CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 503 -> "UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
        String message = switch (status) {
            case 400 -> "词典请求参数无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "词典访问被拒绝";
            case 404 -> "词典记录不存在或不可访问";
            case 405 -> "不支持该词典请求方法";
            case 406, 415 -> "不支持该词典媒体类型";
            case 409 -> "词典状态已变化，请主动核对";
            case 413 -> "词典请求体过大";
            case 503 -> "词典服务暂不可用，请稍后核对";
            default -> "词典请求处理失败";
        };
        return response(status, message, reason);
    }

    private static int status(Exception error) {
        if (error instanceof IdentityException identity) {
            return identity.httpStatus();
        }
        if (error instanceof ApiException api) {
            return api.httpStatus();
        }
        if (error instanceof QueryException query) {
            return query.status();
        }
        if (error instanceof SaTokenException) {
            return 401;
        }
        if (error instanceof HttpRequestMethodNotSupportedException) {
            return 405;
        }
        if (error instanceof HttpMediaTypeNotSupportedException) {
            return 415;
        }
        if (error instanceof HttpMediaTypeNotAcceptableException) {
            return 406;
        }
        if (error instanceof ServletRequestBindingException || error instanceof HttpMessageNotReadableException
                || error instanceof MethodArgumentTypeMismatchException || error instanceof BindException
                || error instanceof ConstraintViolationException || error instanceof HandlerMethodValidationException) {
            return 400;
        }
        if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            return 404;
        }
        if (error instanceof MaxUploadSizeExceededException) {
            return 413;
        }
        if (error instanceof DataAccessException || error instanceof TransactionException) {
            return 503;
        }
        if (error instanceof ResponseStatusException response) {
            int status = response.getStatusCode().value();
            return status == 400 || status == 401 || status == 403 || status == 404 || status == 405 || status == 406
                    || status == 409 || status == 413 || status == 415 || status == 503 ? status : 500;
        }
        return 500;
    }

    private static ResponseEntity<Failure> response(int status, String message, String reason) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new Failure(R.FAILURE, message, null, reason));
    }
}
