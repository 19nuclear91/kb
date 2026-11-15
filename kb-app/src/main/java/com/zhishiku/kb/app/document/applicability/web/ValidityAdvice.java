package com.zhishiku.kb.app.document.applicability.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityErrorCode;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = {"com.zhishiku.kb.app.document.applicability.web", "com.zhishiku.kb.app.document.stewardship.web"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidityAdvice {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        ValidityException.Reason reason;
        if (error instanceof ValidityException domain) {
            reason = domain.reason();
        } else if (error instanceof IdentityException identity && identity.errorCode() == IdentityErrorCode.PASSWORD_CHANGE_REQUIRED) {
            reason = ValidityException.Reason.PASSWORD_CHANGE_REQUIRED;
        } else {
            int status = error instanceof ApiException api ? api.httpStatus()
                    : error instanceof QueryException query ? query.status()
                    : error instanceof SaTokenException ? 401 : error instanceof ServletRequestBindingException ? 400 : 503;
            reason = switch (status) {
                case 400 -> ValidityException.Reason.INVALID_INPUT;
                case 401 -> ValidityException.Reason.UNAUTHORIZED;
                case 403 -> ValidityException.Reason.FORBIDDEN;
                case 404 -> ValidityException.Reason.NOT_FOUND;
                case 409 -> ValidityException.Reason.NO_CHANGE;
                case 413 -> ValidityException.Reason.PAYLOAD_TOO_LARGE;
                default -> ValidityException.Reason.UNAVAILABLE;
            };
        }
        return ResponseEntity.status(reason.status()).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, new ValidityException(reason).getMessage(), null, reason.name()));
    }
}
