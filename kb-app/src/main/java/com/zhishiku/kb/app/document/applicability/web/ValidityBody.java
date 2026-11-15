package com.zhishiku.kb.app.document.applicability.web;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

public final class ValidityBody {
    private ValidityBody() {
    }

    public static ObjectNode read(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw ValidityInput.invalid();
        }
        try {
            return ValidityInput.body(request.getInputStream());
        } catch (IOException ignored) {
            throw ValidityInput.invalid();
        }
    }

    public static long actor() {
        StpUtil.checkLogin();
        return StpUtil.getLoginIdAsLong();
    }
}
