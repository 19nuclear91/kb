package com.zhishiku.kb.app.knowledgegap.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 固定错误投影；不记录异常、候选名称或共享正文。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {KnowledgeGapAssigneeController.class, KnowledgeGapController.class,
        KnowledgeGapAssignmentController.class, KnowledgeGapSolutionController.class, KnowledgeGapClosureController.class,
        KnowledgeGapQueryController.class, KnowledgeGapProtocolController.class})
public class KnowledgeGapAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        return project(error);
    }

    public static ResponseEntity<R<Void>> project(Exception error) {
        int status = 503;
        if (error instanceof QueryException query) {
            status = query.status();
        } else if (error instanceof SaTokenException) {
            status = 401;
        } else if (error instanceof ApiException api) {
            status = api.httpStatus();
        } else if (error instanceof HttpRequestMethodNotSupportedException || error instanceof HttpMediaTypeNotSupportedException
                || error instanceof HttpMediaTypeNotAcceptableException || error instanceof HttpMessageNotReadableException
                || error instanceof MethodArgumentTypeMismatchException || error instanceof ServletRequestBindingException
                || error instanceof BindException || error instanceof ConstraintViolationException
                || error instanceof HandlerMethodValidationException) {
            status = 400;
        } else if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            status = 404;
        } else if (error instanceof MaxUploadSizeExceededException) {
            status = 413;
        } else if (error instanceof ResponseStatusException response) {
            status = response.getStatusCode().value();
        }
        if (status != 400 && status != 401 && status != 403 && status != 404 && status != 409 && status != 413) {
            status = 503;
        }
        String message = switch (status) {
            case 400 -> "知识问题单请求无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "知识问题单访问被拒绝";
            case 404 -> "知识问题单不存在或不可访问";
            case 409 -> "知识问题单状态或候选已变化";
            case 413 -> "知识问题单请求过大";
            default -> "知识问题单服务暂不可用";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(R.fail(message));
    }
}
