package com.zhishiku.kb.app.notification.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 固定错误投影；不回显原始异常、目标或身份信息，也不写日志。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        int status = 503;
        if (error instanceof QueryException query) status = query.status();
        else if (error instanceof SaTokenException) status = 401;
        else if (error instanceof ApiException api) status = api.httpStatus();
        else if (error instanceof MethodArgumentTypeMismatchException) status = 400;
        if (status != 400 && status != 401 && status != 403 && status != 404) status = 503;
        String message = switch (status) {
            case 400 -> "通知请求参数无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "通知访问被拒绝";
            case 404 -> "通知不存在";
            default -> "通知服务暂不可用";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(R.fail(message));
    }
}
