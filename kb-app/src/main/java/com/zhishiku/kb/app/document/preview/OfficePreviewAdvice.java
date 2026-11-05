package com.zhishiku.kb.app.document.preview;

import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OfficePreviewAdvice {
    @ExceptionHandler(OfficePreviewFailure.class)
    public ResponseEntity<R<Void>> failure(OfficePreviewFailure failure) {
        return ResponseEntity.status(failure.status()).header("Cache-Control", "no-store")
                .header("Referrer-Policy", "no-referrer").body(R.fail(failure.getMessage()));
    }
}
