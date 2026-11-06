package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.lifecycle.OutputStopEvidenceService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class OutputStopEvidenceController {
    private final OutputStopEvidenceService service;

    public OutputStopEvidenceController(OutputStopEvidenceService service) {
        this.service = service;
    }

    @PostMapping(path = AuthenticationSignature.STOP_PATH)
    public ResponseEntity<Void> stopped(HttpServletRequest request) {
        byte[] body;
        AuthenticationSignature.Headers headers;
        try {
            if (request.getQueryString() != null) {
                throw new IllegalArgumentException();
            }
            body = request.getInputStream().readNBytes(AuthenticationSignature.STOP_MAX_BYTES + 1);
            if (body.length == 0 || body.length > AuthenticationSignature.STOP_MAX_BYTES) {
                throw new IllegalArgumentException();
            }
            headers = new AuthenticationSignature.Headers(request.getHeader(AuthenticationSignature.CALLER),
                    Long.parseLong(request.getHeader(AuthenticationSignature.TIMESTAMP)), request.getHeader(AuthenticationSignature.NONCE),
                    request.getHeader(AuthenticationSignature.SIGNATURE));
        } catch (Exception invalid) {
            throw ApiException.forbidden("内部观测请求无效");
        }
        service.accept(request.getMethod(), request.getRequestURI(), body, headers);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
