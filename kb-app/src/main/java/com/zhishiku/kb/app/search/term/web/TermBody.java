package com.zhishiku.kb.app.search.term.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.term.TermInput;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/** HTTP 适配仅读取有界正文，不从 URL 获取任何管理查询条件。 */
public final class TermBody {
    private TermBody() {
    }

    public static void rejectQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw TermInput.invalid();
        }
    }

    public static ObjectNode read(HttpServletRequest request) {
        rejectQuery(request);
        try {
            return TermInput.body(request.getInputStream());
        } catch (IOException invalid) {
            throw TermInput.invalid();
        }
    }
}
