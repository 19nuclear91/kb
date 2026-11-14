package com.zhishiku.kb.app.notification.web;

import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.security.NotificationContract;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.util.UUID;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** 通知端点的严格输入：不接受正文、收件人、标题或外部目标。 */
public final class NotificationInput {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private NotificationInput() {
    }

    public static UUID notificationId(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw QueryException.invalid();
        }
        return UUID.fromString(value);
    }

    public static NotificationContract contract(HttpServletRequest request) {
        var values = request.getHeaders(NotificationContract.HEADER);
        return NotificationContract.parse(values == null ? List.of() : Collections.list(values));
    }

    public static void noBody(HttpServletRequest request) throws IOException {
        var cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if ((cached != null && cached.getContentAsByteArray().length > 0)
                || request.getInputStream().read() != -1) {
            throw QueryException.invalid();
        }
    }

    public static void noPayload(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw QueryException.invalid();
        }
        noBody(request);
    }
}
