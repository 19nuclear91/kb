package com.zhishiku.kb.app.query.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.AdminQueryEndpoint;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 仅处理标记的新查询端点，不记录原始异常或用户筛选值。
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = AdminQueryEndpoint.class)
public class AdminQueryAdvice implements ResponseBodyAdvice<Object> {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        int status = 500;
        if (error instanceof QueryException query) status = query.status();
        else if (error instanceof SaTokenException) status = 401;
        else if (error instanceof ApiException api) status = api.httpStatus();
        else if (error instanceof DataAccessException || error instanceof TransactionException) status = 503;
        else if (error instanceof MethodArgumentTypeMismatchException) status = 400;
        String message = switch (status) {
            case 400 -> "查询参数无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "查询访问被拒绝";
            case 404 -> "目标不存在或不可访问";
            case 503 -> "查询服务暂不可用";
            default -> "服务内部错误";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(R.fail(message));
    }

    @Override
    public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
                                  Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        response.getHeaders().setCacheControl("no-store");
        return body;
    }
}
