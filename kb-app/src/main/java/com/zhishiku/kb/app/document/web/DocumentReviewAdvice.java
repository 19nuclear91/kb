package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 审核故障只返回固定错误，无原文日志。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DocumentReviewController.class, DocumentReviewApprovalController.class, CandidateIndexRefreshController.class})
public class DocumentReviewAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Map<String, String>>> failure(Exception error) {
        ReviewError code = ReviewError.SERVICE_UNAVAILABLE;
        if (error instanceof ReviewFailure review) code = review.error();
        else {
            int status = error instanceof QueryException query ? query.status()
                    : error instanceof SaTokenException ? 401
                    : error instanceof IdentityException identity ? identity.httpStatus()
                    : error instanceof ApiException api ? api.httpStatus()
                    : error instanceof IllegalArgumentException || error instanceof HttpMediaTypeNotSupportedException ? 400 : 503;
            code = switch (status) {
                case 400 -> ReviewError.INVALID_REQUEST;
                case 401 -> ReviewError.UNAUTHENTICATED;
                case 403 -> ReviewError.FORBIDDEN;
                case 404 -> ReviewError.NOT_FOUND;
                case 409 -> ReviewError.STATE_CONFLICT;
                default -> ReviewError.SERVICE_UNAVAILABLE;
            };
        }
        return ResponseEntity.status(code.status()).header("Cache-Control", "no-store")
                .body(new R<>(R.FAILURE, "审核请求未完成", Map.of("errorCode", code.name())));
    }
}
