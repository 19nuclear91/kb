package com.zhishiku.kb.app.document.changenote.web;

import cn.dev33.satoken.exception.SaTokenException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.zhishiku.kb.app.document.changenote.service.ChangeNoteException;
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

@RestControllerAdvice(basePackages = {"com.zhishiku.kb.app.document.changenote.web"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ChangeNoteAdvice {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Failure(int code, String message, Object data, String reasonCode) {
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Failure> failure(Exception error) {
        ChangeNoteException.Reason reason;
        if (error instanceof ChangeNoteException domain) {
            reason = domain.reason();
        } else if (error instanceof IdentityException identity && identity.errorCode() == IdentityErrorCode.PASSWORD_CHANGE_REQUIRED) {
            reason = ChangeNoteException.Reason.PASSWORD_CHANGE_REQUIRED;
        } else {
            int status = error instanceof ApiException api ? api.httpStatus()
                    : error instanceof SaTokenException ? 401 : error instanceof ServletRequestBindingException ? 400 : 503;
            reason = switch (status) {
                case 400 -> ChangeNoteException.Reason.INVALID_INPUT;
                case 401 -> ChangeNoteException.Reason.UNAUTHORIZED;
                case 403 -> ChangeNoteException.Reason.FORBIDDEN;
                case 404 -> ChangeNoteException.Reason.NOT_FOUND;
                case 409 -> ChangeNoteException.Reason.NO_CHANGE;
                case 413 -> ChangeNoteException.Reason.PAYLOAD_TOO_LARGE;
                default -> ChangeNoteException.Reason.UNAVAILABLE;
            };
        }
        return ResponseEntity.status(reason.status()).header("Cache-Control", "no-store")
                .body(new Failure(R.FAILURE, new ChangeNoteException(reason).getMessage(), null, reason.name()));
    }
}
