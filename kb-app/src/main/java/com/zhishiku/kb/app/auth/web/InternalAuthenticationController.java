package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.service.InternalAuthenticationService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthHeaders;
import com.zhishiku.kb.common.security.AuthenticationCheck;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 固定内部操作；与多轮会话、钉钉订阅是否启用无关。 */
@Hidden
@RestController
public class InternalAuthenticationController {
    private final InternalAuthenticationService service;

    public InternalAuthenticationController(InternalAuthenticationService service) {
        this.service = service;
    }

    @PostMapping(path = AuthenticationSignature.PATH, produces = "application/json")
    public ResponseEntity<AuthenticationCheck> check(HttpServletRequest request) {
        AuthenticationSignature.Headers headers;
        try {
            if (request.getQueryString() != null || request.getInputStream().read() != -1) {
                throw new IllegalArgumentException();
            }
            headers = new AuthenticationSignature.Headers(request.getHeader(AuthenticationSignature.CALLER),
                    Long.parseLong(request.getHeader(AuthenticationSignature.TIMESTAMP)),
                    request.getHeader(AuthenticationSignature.NONCE), request.getHeader(AuthenticationSignature.SIGNATURE));
        } catch (Exception invalid) {
            throw ApiException.forbidden("内部认证请求无效");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.check(request.getMethod(),
                request.getRequestURI(), null, request.getHeader(AuthHeaders.SA_TOKEN), headers));
    }
}
