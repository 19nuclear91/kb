package com.zhishiku.kb.app.document.imports.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.imports.service.ImportAccess;
import com.zhishiku.kb.app.document.imports.service.ImportCapacity;
import com.zhishiku.kb.app.document.imports.service.ImportException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.api.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 在 DispatcherServlet 的 multipart 解析之前准入，不读取请求参数或文件流。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class ImportAdmissionFilter extends OncePerRequestFilter {
    private final ImportAccess access;
    private final ImportCapacity capacity;
    private final ObjectMapper json;

    public ImportAdmissionFilter(ImportAccess access, ImportCapacity capacity, ObjectMapper json) {
        this.access = access;
        this.capacity = capacity;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !UrlPathHelper.defaultInstance.getPathWithinApplication(request)
                        .matches("/api/document-imports/[^/]+/items/[^/]+/file");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ImportCapacity.Reservation reservation;
        try {
            var tokens = Collections.list(request.getHeaders("satoken"));
            if (tokens.size() > 1) {
                throw new ImportException(ImportException.Reason.INVALID_INPUT);
            }
            long userId = access.current(tokens.isEmpty() ? null : tokens.getFirst()).userId();
            reservation = capacity.acquire(userId);
        } catch (RuntimeException failure) {
            reject(response, failure);
            return;
        }
        try (reservation) {
            // 新增文件入口为同步处理，包含校验、SDK 调用及最终事务的实际退出。
            chain.doFilter(request, response);
        }
    }

    private void reject(HttpServletResponse response, RuntimeException failure) throws IOException {
        int status = 503;
        if (failure instanceof ImportException known) {
            status = known.reason().status();
        } else if (failure instanceof ApiException known) {
            status = known.httpStatus();
        }
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String message = status == 429 ? "导入上传繁忙，请稍后主动继续" : "导入请求未完成，请核对当前状态";
        json.writeValue(response.getOutputStream(), R.fail(message));
    }
}
