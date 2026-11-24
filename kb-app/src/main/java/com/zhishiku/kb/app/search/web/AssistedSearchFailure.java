package com.zhishiku.kb.app.search.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.search.assistance.SearchContextFailure;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.validation.ConstraintViolationException;

/** 增强端点独立固定失败，不改变旧搜索数组或失败契约，不打印异常链。 */
public final class AssistedSearchFailure {
    private AssistedSearchFailure() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    public static ResponseEntity<Failure> project(Exception error) {
        if (error instanceof HttpMessageNotReadableException) {
            for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof SearchFailure known) {
                    error = known;
                    break;
                }
            }
        }
        int status = status(error);
        String reason = switch (status) {
            case 400 -> "INVALID_INPUT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406, 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 409 -> "ASSISTANCE_STALE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 429 -> "USER_LIMIT";
            case 503 -> "SEARCH_UNAVAILABLE";
            default -> "INTERNAL_ERROR";
        };
        if (error instanceof SearchFailure failure) {
            reason = switch (failure.kind()) {
                case ASSISTANCE_UNAVAILABLE, ASSISTANCE_LIMIT_EXCEEDED, ASSISTANCE_STALE, USER_LIMIT, CAPACITY -> failure.kind().name();
                default -> reason;
            };
        }
        var response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (error instanceof SearchFailure failure && failure.retryAfter() > 0
                && (failure.kind() == SearchFailure.Kind.USER_LIMIT || failure.kind() == SearchFailure.Kind.CAPACITY)) {
            response.header("Retry-After", Integer.toString(failure.retryAfter()));
        }
        return response.body(new Failure(R.FAILURE, "搜索请求未完成，请主动核对或重试", null, reason));
    }

    private static int status(Exception error) {
        if (error instanceof SearchFailure failure) {
            return failure.status();
        }
        if (error instanceof SearchContextFailure failure) {
            return failure.status();
        }
        if (error instanceof ApiException api) {
            return allowed(api.httpStatus());
        }
        if (error instanceof SaTokenException) {
            return 401;
        }
        if (error instanceof HttpMessageNotReadableException || error instanceof ServletRequestBindingException
                || error instanceof BindException || error instanceof ConstraintViolationException) {
            return 400;
        }
        if (error instanceof HttpRequestMethodNotSupportedException) {
            return 405;
        }
        if (error instanceof HttpMediaTypeNotSupportedException) {
            return 415;
        }
        if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            return 404;
        }
        if (error instanceof DataAccessException || error instanceof CannotCreateTransactionException || error instanceof TransactionTimedOutException) {
            return 503;
        }
        if (error instanceof ResponseStatusException response) {
            return allowed(response.getStatusCode().value());
        }
        return 500;
    }

    private static int allowed(int status) {
        return switch (status) {
            case 400, 401, 403, 404, 405, 406, 409, 413, 415, 429, 503 -> status;
            default -> 500;
        };
    }
}
