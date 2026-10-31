package com.zhishiku.kb.app.config;

import cn.dev33.satoken.exception.SaTokenException;
import com.zhishiku.kb.app.search.model.SearchFailure;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理，把异常转换为统一响应结构。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(SearchFailure.class)
    public ResponseEntity<R<Void>> handleSearch(SearchFailure failure) {
        log.warn("搜索处理失败 stage=search category={} status={}", failure.kind(), failure.status());
        var response = ResponseEntity.status(failure.status()).header("Cache-Control", "no-store");
        if (failure.retryAfter() > 0) {
            response.header("Retry-After", Integer.toString(failure.retryAfter()));
        }
        return response.body(R.fail(failure.safeMessage()));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<R<Void>> handleIdentity(IdentityException e) {
        if (isSearchRequest()) {
            log.debug("搜索身份校验失败 stage=identity category={} status={}", e.errorCode(), e.httpStatus());
            return ResponseEntity.status(e.httpStatus()).body(R.fail("搜索访问被拒绝"));
        }
        log.debug("身份/部门操作失败 code={} reason={}", e.errorCode(), e.getMessage());
        return ResponseEntity.status(e.httpStatus()).body(R.fail(e.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<R<Void>> handleApi(ApiException e) {
        if (isSearchRequest()) {
            log.warn("搜索处理失败 stage=api status={}", e.httpStatus());
            return ResponseEntity.status(e.httpStatus()).body(R.fail("搜索请求处理失败"));
        }
        return ResponseEntity.status(e.httpStatus()).body(R.fail(e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<R<Void>> handleInvalidRequest(Exception e) {
        // 不回显校验对象，密码或请求正文可能包含在异常消息里。
        return ResponseEntity.badRequest().body(R.fail("请求参数或请求体无效"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUploadTooLarge(Exception e) {
        return ResponseEntity.status(413).body(R.fail("文件超过上传大小限制"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethodNotAllowed(Exception e) {
        return ResponseEntity.status(405).body(R.fail("不支持该请求方法"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleUnsupportedMediaType(Exception e) {
        return ResponseEntity.status(415).body(R.fail("不支持该媒体类型"));
    }

    @ExceptionHandler({NoResourceFoundException.class,
            NoHandlerFoundException.class})
    public ResponseEntity<R<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(404).body(R.fail("请求的资源不存在"));
    }

    @ExceptionHandler(SaTokenException.class)
    public ResponseEntity<R<Void>> handleAuth(SaTokenException e) {
        if (isSearchRequest()) {
            log.debug("搜索认证失败 stage=auth category=unauthorized status=401");
        } else {
            log.debug("Sa-Token 鉴权失败 reason={}", e.getMessage());
        }
        // 未认证是 401（会话缺失或失效），不是 500：500 会把一次可预期的拒绝
        // 报成服务端故障。403 留给已认证但无资格（IdentityException.forbidden），
        // 未认证与无资格的区分在网关与各服务间保持一致（设计 D11）。
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail("登录状态无效或已过期"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        if (isSearchRequest()) {
            log.error("搜索处理失败 stage=unhandled category=internal status=500");
        } else {
            log.error("未处理异常", e);
        }
        return failure("服务内部错误");
    }

    private static boolean isSearchRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String path = request.getRequestURI();
            return "/api/search".equals(path) || path.startsWith("/api/conversations")
                    || path.startsWith("/internal/conversations");
        }
        return false;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<R<Void>> handleStatus(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).header("Cache-Control", "no-store")
                .body(R.fail("会话请求未被接受"));
    }

    private static ResponseEntity<R<Void>> failure(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(message));
    }
}
