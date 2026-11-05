package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.document.service.DocumentManagementService;
import com.zhishiku.kb.common.file.DocumentFilePolicy;
import com.zhishiku.kb.common.office.OfficeServiceSignature;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/** 内部网络入口仍需独立服务签名，公网网关不得提供通配转发。 */
@RestController
public class OfficeInternalController {
    private final OfficePreviewSettings settings;
    private final OfficePreviewSessions sessions;
    private final OfficeDocumentConfiguration configuration;
    private final DocumentManagementService documents;
    private final ObjectMapper json;

    public OfficeInternalController(OfficePreviewSettings settings, OfficePreviewSessions sessions,
                                     OfficeDocumentConfiguration configuration, DocumentManagementService documents,
                                     ObjectMapper json) {
        this.settings = settings;
        this.sessions = sessions;
        this.configuration = configuration;
        this.documents = documents;
        this.json = json;
    }

    @PostMapping("/internal/office-preview/{operation:bootstrap|authorize|configuration}")
    public ResponseEntity<?> operation(@PathVariable String operation, HttpServletRequest request) {
        JsonNode body = request(request, "/internal/office-preview/" + operation);
        String id = body.path("sessionId").asText();
        if (operation.equals("bootstrap")) {
            return reply(sessions.activate(id, body.path("launchToken").asText(), body.path("origin").asText()));
        }
        var authorized = sessions.authorize(id, body.path("cookie").asText());
        Map<String, Object> config = configuration.configuration(authorized);
        if (operation.equals("configuration")) {
            return reply(config);
        }
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("sessionId", id);
        grant.put("documentKey", authorized.session().documentKey());
        grant.put("expiresAt", authorized.session().expiresAt());
        grant.put("sourceUrl", configuration.sourceUrl(id));
        grant.put("configurationToken", config.get("token"));
        return reply(grant);
    }

    @GetMapping("/internal/office-preview/sessions/{sessionId}/source")
    public ResponseEntity<StreamingResponseBody> source(@PathVariable String sessionId, HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) || request.getQueryString() != null) {
            throw new OfficePreviewFailure(401);
        }
        configuration.requireSourceSignature(sessionId, request.getHeader("Authorization"));
        var granted = sessions.source(sessionId);
        var file = granted.file();
        // 沿用原件策略，损坏、宏、嵌入对象及加密输入不能经此入口放宽。
        try (var inspect = documents.open(file)) {
            DocumentFilePolicy.inspect(inspect, file.getFileName(), settings.getMaxFileBytes());
        } catch (IllegalArgumentException unsafe) {
            throw new OfficePreviewFailure(415);
        } catch (Exception unavailable) {
            throw new OfficePreviewFailure(503);
        }
        StreamingResponseBody body = output -> {
            try (var input = documents.open(file)) {
                byte[] buffer = new byte[16384];
                long count = 0;
                for (int size; (size = input.read(buffer)) != -1;) {
                    count += size;
                    if (count > settings.getMaxFileBytes() || count > file.getFileSize()) {
                        throw new IOException("原件读取超出限制");
                    }
                    output.write(buffer, 0, size);
                }
                if (count != file.getFileSize()) {
                    throw new IOException("原件读取未完成");
                }
            } catch (Exception failure) {
                throw new IOException("原件读取未完成");
            }
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.getFileSize()).header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'").body(body);
    }

    private JsonNode request(HttpServletRequest request, String path) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384 || request.getQueryString() != null
                    || !OfficeServiceSignature.verify(settings.getGatewaySecret(), path, bytes,
                    request.getHeader(OfficeServiceSignature.HEADER), Clock.systemUTC())) {
                throw new OfficePreviewFailure(401);
            }
            JsonNode result = json.readTree(bytes);
            if (result == null || !result.isObject()) {
                throw new OfficePreviewFailure(400);
            }
            return result;
        } catch (OfficePreviewFailure safe) {
            throw safe;
        } catch (Exception invalid) {
            throw new OfficePreviewFailure(400);
        }
    }

    private static ResponseEntity<?> reply(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
