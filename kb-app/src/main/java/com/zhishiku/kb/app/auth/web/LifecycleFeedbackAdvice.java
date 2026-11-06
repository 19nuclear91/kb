package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 只投影固定类别；异常正文可能含员工标识、秘密或 SQL，禁止回显和记录。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {LifecycleWriteController.class, EmergencyManagementController.class,
        LifecycleRestorationController.class})
public class LifecycleFeedbackAdvice implements ResponseBodyAdvice<Object> {
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        int status = 503;
        String reason = "DEPENDENCY_UNAVAILABLE";
        if (error instanceof ApiException api) {
            status = api.httpStatus();
            reason = switch (status) {
                case 400 -> "INVALID_REQUEST";
                case 401 -> "UNAUTHORIZED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 409 -> "CONFLICT";
                default -> "DEPENDENCY_UNAVAILABLE";
            };
            if (error instanceof IdentityException identity && status == 409 && identity.reason().isPresent()) {
                reason = identity.reason().orElseThrow().name();
            }
        } else if (error instanceof SaTokenException) {
            status = 401;
            reason = "UNAUTHORIZED";
        } else if (error instanceof HttpMessageNotReadableException || error instanceof MethodArgumentTypeMismatchException) {
            status = 400;
            reason = "INVALID_REQUEST";
        }
        String message = switch (status) {
            case 400 -> "操作参数无效，请重新核对";
            case 401 -> "登录或本地凭据验证无效";
            case 403 -> "缺少操作资格，请重新确认权限";
            case 404 -> "目标不存在或不可管理";
            case 409 -> "当前事实已变化或不满足操作条件，请重新核对";
            default -> "生命周期服务暂不可用，请稍后重新核对";
        };
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, message, null, reason));
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
