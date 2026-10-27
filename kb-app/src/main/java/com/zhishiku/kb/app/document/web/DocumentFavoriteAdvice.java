package com.zhishiku.kb.app.document.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 固定错误投影，避免原始依赖错误进入响应和日志。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DocumentFavoriteController.class)
public class DocumentFavoriteAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        int status = 503;
        if (error instanceof QueryException query) status = query.status();
        else if (error instanceof SaTokenException) status = 401;
        else if (error instanceof ApiException api) status = api.httpStatus();
        else if (error instanceof MethodArgumentTypeMismatchException) status = 400;
        else if (error instanceof HttpMediaTypeNotSupportedException) status = 415;
        if (status >= 500) status = 503;
        String message = switch (status) {
            case 400 -> "收藏请求参数无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "收藏操作被拒绝";
            case 404 -> "文档不存在或当前不可查看";
            case 415 -> "收藏请求需要 JSON 格式";
            default -> "收藏服务暂不可用";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(R.fail(message));
    }
}
