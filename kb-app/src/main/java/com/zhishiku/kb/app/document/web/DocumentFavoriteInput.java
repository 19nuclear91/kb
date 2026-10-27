package com.zhishiku.kb.app.document.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.zhishiku.kb.app.document.service.DocumentFavoriteService;
import com.zhishiku.kb.app.query.QueryException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 新收藏端点的流式严格解析，不修改全局 JSON 设置。 */
public final class DocumentFavoriteInput {
    private static final JsonFactory JSON = new JsonFactory();

    private DocumentFavoriteInput() {
    }

    /**
     * 批量状态请求。
     *
     * @param documentIds 1 至 100 个不同的正整数文档标识
     */
    @Schema(description = "本人收藏状态查询")
    public record StatusRequest(
            @Schema(description = "1 至 100 个不同的正整数文档标识") List<Long> documentIds) {
        public StatusRequest {
            DocumentFavoriteService.validateIds(documentIds);
            documentIds = List.copyOf(documentIds);
        }
    }

    public static StatusRequest parse(InputStream input) {
        try (var parser = JSON.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_OBJECT || parser.nextToken() != JsonToken.FIELD_NAME
                    || !"documentIds".equals(parser.currentName()) || parser.nextToken() != JsonToken.START_ARRAY) {
                throw QueryException.invalid();
            }
            List<Long> ids = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT || ids.size() >= 100) throw QueryException.invalid();
                ids.add(parser.getLongValue());
            }
            if (parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) throw QueryException.invalid();
            return new StatusRequest(ids);
        } catch (IOException invalid) {
            throw QueryException.invalid();
        }
    }

    public static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) throw QueryException.invalid();
    }

    public static void noPayload(HttpServletRequest request) throws IOException {
        noQuery(request);
        var cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if ((cached != null && cached.getContentAsByteArray().length > 0)
                || request.getInputStream().read() != -1) throw QueryException.invalid();
    }
}
