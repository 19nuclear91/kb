package com.zhishiku.kb.app.conversation.web;

import com.zhishiku.kb.app.conversation.service.InternalConversationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部请求不由公开网关转发，仍须独立验证签名。
 */
@RestController
@Hidden
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class InternalConversationController {
    private final InternalConversationService service;

    public InternalConversationController(InternalConversationService service) {
        this.service = service;
    }

    @PostMapping(path = "/internal/conversations/{operation}", consumes = "application/json", produces = "application" +
            "/json")
    public ResponseEntity<Object> invoke(@PathVariable String operation, @RequestBody byte[] body,
                                         HttpServletRequest request) {
        RequestSignature.Headers headers;
        try {
            headers = new RequestSignature.Headers(request.getHeader("X-Service-Caller"),
                    Long.parseLong(request.getHeader("X-Service-Timestamp")), request.getHeader("X-Service-Nonce"),
                    request.getHeader("X-Service-Signature"));
        } catch (RuntimeException error) {
            throw ApiException.forbidden("内部请求身份无效");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.invoke(operation,
                request.getMethod(), request.getRequestURI(), body, request.getHeader("satoken"), headers));
    }
}

