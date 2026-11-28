package com.zhishiku.kb.app.controller.health;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.service.health.HealthFailure;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 只输出固定错误消息和原因头，不记录或回显底层异常。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.zhishiku.kb.app.controller.health")
public class HealthAdvice {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> failure(Exception error) {
        int status = error instanceof HealthFailure health ? health.status()
                : error instanceof QueryException query ? query.status()
                : error instanceof ApiException api ? api.httpStatus()
                : error instanceof SaTokenException ? 401
                : error instanceof ServletRequestBindingException ? 400 : 503;
        String reason = error instanceof HealthFailure health ? health.reason() : switch (status) {
            case 400 -> "INVALID_INPUT";
            case 401 -> "UNAUTHORIZED";
            case 403 -> "FORBIDDEN";
            case 404 -> "NOT_FOUND";
            default -> "UNAVAILABLE";
        };
        String message = switch (status) {
            case 400 -> "健康雷达请求无效";
            case 401 -> "登录状态无效或已过期";
            case 403 -> "当前身份不可用或缺少健康雷达资格";
            case 404 -> "空间不存在或不可管理";
            case 409 -> "SPACE_ARCHIVED".equals(reason) ? "空间已归档，诊断暂停" : "业务日期已变化，请从首页重新查询";
            default -> "健康雷达暂不可用";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .header("X-Kb-Health-Error", reason).body(R.fail(message));
    }
}
