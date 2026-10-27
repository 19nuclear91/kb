package com.zhishiku.kb.app.comment.web;

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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** 评论故障只返回固定错误码，不回显正文、资源明细或原始异常，也不写日志。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DocumentCommentController.class)
public class DocumentCommentAdvice {
    /** 对外固定错误码。 */
    public enum Code {
        INVALID_REQUEST(400), UNAUTHENTICATED(401), FORBIDDEN(403), NOT_FOUND(404), STATE_CONFLICT(409),
        SERVICE_UNAVAILABLE(503);

        private final int status;

        Code(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }

        static Code of(int status) {
            for (Code code : values()) {
                if (code.status == status) {
                    return code;
                }
            }
            return SERVICE_UNAVAILABLE;
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Map<String, String>>> failure(Exception error) {
        int status = error instanceof QueryException query ? query.status()
                : error instanceof SaTokenException ? 401
                : error instanceof IdentityException identity ? identity.httpStatus()
                : error instanceof ApiException api ? api.httpStatus()
                : error instanceof MethodArgumentTypeMismatchException || error instanceof HttpMediaTypeNotSupportedException ? 400
                : 503;
        var code = Code.of(status);
        return ResponseEntity.status(code.status()).header("Cache-Control", "no-store")
                .body(new R<>(R.FAILURE, "评论请求未完成", Map.of("errorCode", code.name())));
    }
}
