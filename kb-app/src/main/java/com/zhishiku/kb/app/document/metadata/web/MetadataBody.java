package com.zhishiku.kb.app.document.metadata.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.metadata.support.MetadataInput;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/** HTTP 请求适配留在 web 层，有界解码由纯输入工具完成。 */
public final class MetadataBody {
    private MetadataBody() {
    }

    public static ObjectNode read(HttpServletRequest request, int limit) {
        if (request.getQueryString() != null) {
            throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
        }
        try {
            return MetadataInput.body(request.getInputStream(), limit);
        } catch (IOException ignored) {
            throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
        }
    }
}
