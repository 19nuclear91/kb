package com.zhishiku.kb.app.config;

import com.zhishiku.kb.app.auth.web.InternalAuthenticationController;
import com.zhishiku.kb.app.conversation.web.InternalConversationController;
import com.zhishiku.kb.common.security.AccountRevokedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 固定结果仅用于内部协议；服务签名失败不经过此分支。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {InternalAuthenticationController.class, InternalConversationController.class})
public class InternalIdentityFailureAdvice {
    @ExceptionHandler(AccountRevokedException.class)
    public ResponseEntity<Void> revoked(AccountRevokedException failure) {
        return ResponseEntity.status(failure.httpStatus()).cacheControl(CacheControl.noStore())
                .header(AccountRevokedException.HEADER, AccountRevokedException.CODE).build();
    }
}
