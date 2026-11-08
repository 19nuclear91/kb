package com.zhishiku.kb.app.document.imports.web;

import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** 错误不拼接原始异常，未知处理结果留给持久项核对。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ImportController.class)
public class ImportExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handle(Exception error) {
        int status = 503;
        if (error instanceof ImportException failure) {
            status = failure.reason().status();
        } else if (error instanceof ApiException failure) {
            status = failure.httpStatus();
        } else if (error instanceof MissingRequestHeaderException) {
            status = 400;
        } else if (error instanceof MaxUploadSizeExceededException) {
            status = 413;
        } else if (error instanceof MultipartException) {
            status = 400;
        } else if (error instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(R.fail("导入请求未完成，请核对当前状态"));
    }
}
