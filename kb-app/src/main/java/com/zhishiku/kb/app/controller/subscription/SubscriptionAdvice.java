package com.zhishiku.kb.app.controller.subscription;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 订阅异常只返回固定类别，日志和响应均不携带私有输入或底层错误。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.zhishiku.kb.app.controller.subscription")
public class SubscriptionAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        return project(error);
    }

    public static ResponseEntity<R<Void>> project(Exception error) {
        int status = 503;
        String reason = "UNAVAILABLE";
        if (error instanceof SubscriptionFailure failure) {
            status = failure.status();
            reason = failure.reason();
        } else if (error instanceof QueryException query) {
            status = query.status();
            reason = "INVALID_INPUT";
        } else if (error instanceof ApiException api) {
            status = api.httpStatus();
            reason = status == 401 ? "UNAUTHORIZED" : "FORBIDDEN";
        } else if (error instanceof SaTokenException) {
            status = 401;
            reason = "UNAUTHORIZED";
        } else if (error instanceof HttpMediaTypeNotSupportedException || error instanceof HttpRequestMethodNotSupportedException) {
            status = 400;
            reason = "INVALID_INPUT";
        }
        String message = switch (status) {
            case 400 -> "知识订阅请求无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "当前身份不可用或需要修改密码";
            case 404 -> "订阅目标不存在或不可访问";
            case 409 -> "订阅状态或原请求已变化，请重新核对";
            case 413 -> "知识订阅请求过大";
            default -> "知识订阅暂不可用";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .header("X-Kb-Subscription-Error", reason).body(R.fail(message));
    }
}
