package com.zhishiku.kb.app.comment.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.query.QueryException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/** 评论端点的有界严格解析：只接受 body 与可选 parentId，拒绝重复、未知字段及身份、时间或删除状态覆盖。 */
public final class CommentInput {
    /** 4000 个补充平面字符以 \\uXXXX 转义时约 48 KiB；正文长度仍由 CommentText 按 code point 判定。 */
    static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of("body", "parentId");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(MAX_BYTES).maxNumberLength(19).build()).build())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private CommentInput() {
    }

    /**
     * 创建评论请求。
     *
     * @param body 纯文本正文，去首尾空白后 1–4000 个 Unicode code point
     * @param parentId 回复的根评论标识；发表根评论时省略
     */
    @Schema(description = "发表评论或单层回复；身份、时间与删除状态只由服务端决定")
    public record CreateRequest(
            @Schema(description = "纯文本正文，去首尾空白后 1–4000 个 Unicode code point，允许换行") String body,
            @Schema(description = "回复的根评论 UUID；发表根评论时省略") UUID parentId) {
    }

    public static CreateRequest create(InputStream input) {
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw QueryException.invalid();
            }
            try (var parser = JSON.createParser(bytes)) {
                JsonNode node = JSON.readTree(parser);
                if (node == null || !node.isObject() || parser.nextToken() != null) {
                    throw QueryException.invalid();
                }
                var names = node.fieldNames();
                while (names.hasNext()) {
                    if (!FIELDS.contains(names.next())) {
                        throw QueryException.invalid();
                    }
                }
                JsonNode body = node.get("body");
                if (body == null || !body.isTextual()) {
                    throw QueryException.invalid();
                }
                JsonNode parent = node.get("parentId");
                if (parent != null && (!parent.isTextual() || !parent.textValue().matches(UUID_PATTERN))) {
                    throw QueryException.invalid();
                }
                return new CreateRequest(body.textValue(), parent == null ? null : UUID.fromString(parent.textValue()));
            }
        } catch (IOException | IllegalArgumentException invalid) {
            throw QueryException.invalid();
        }
    }

    public static UUID commentId(String value) {
        if (value == null || !value.matches(UUID_PATTERN)) {
            throw QueryException.invalid();
        }
        return UUID.fromString(value);
    }

    public static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null || !request.getParameterMap().isEmpty()) {
            throw QueryException.invalid();
        }
    }

    /** 读取端点不接受请求体；删除端点由响应过滤器预先缓存首字节，表单过滤器消费后仍能判定原正文非空。 */
    public static void noPayload(HttpServletRequest request) throws IOException {
        var cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if ((cached != null && cached.getContentAsByteArray().length > 0) || request.getInputStream().read() != -1) {
            throw QueryException.invalid();
        }
    }
}
