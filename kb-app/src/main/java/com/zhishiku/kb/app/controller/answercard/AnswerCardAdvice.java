package com.zhishiku.kb.app.controller.answercard;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 私有输入和依赖异常只投影固定消息，不记录底层异常。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AnswerCardController.class)
public class AnswerCardAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        return project(error);
    }

    public static ResponseEntity<R<Void>> project(Exception error) {
        int status = 503;
        if (error instanceof AnswerCardFailure failure) {
            status = failure.status();
        } else if (error instanceof QueryException query) {
            status = query.status();
        } else if (error instanceof ApiException api) {
            status = api.httpStatus();
        } else if (error instanceof SaTokenException) {
            status = 401;
        } else if (error instanceof HttpMediaTypeNotSupportedException || error instanceof HttpRequestMethodNotSupportedException) {
            status = 400;
        } else if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            status = 404;
        }
        String message = switch (status) {
            case 400 -> "标准答案请求无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "标准答案操作资格不足";
            case 404 -> "标准答案不存在或不可访问";
            case 409 -> "标准答案状态、来源或人员已变化，请重新核对";
            case 413 -> "标准答案请求过大";
            case 429 -> "标准答案搜索繁忙，请稍后重试";
            default -> "标准答案功能暂不可用";
        };
        var response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == 429) {
            response.header("Retry-After", "1");
        }
        return response.body(R.fail(message));
    }
}
