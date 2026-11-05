package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zhishiku.kb.common.office.OfficeCrypto;
import com.zhishiku.kb.common.office.OfficeProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OfficeDocumentConfiguration {
    private static final String JWT_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
    private final OfficePreviewSettings settings;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public OfficeDocumentConfiguration(OfficePreviewSettings settings, ObjectMapper json) {
        this(settings, json, Clock.systemUTC());
    }

    public OfficeDocumentConfiguration(OfficePreviewSettings settings, ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.json = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.clock = clock;
    }

    public Map<String, Object> configuration(OfficePreviewSessions.Authorized authorized) {
        var session = authorized.session();
        var file = authorized.file();
        String extension = OfficeProtocol.extension(file.getFileName());
        Map<String, Object> permissions = new LinkedHashMap<>();
        for (String name : new String[]{"edit", "download", "print", "comment", "review", "fillForms", "modifyContentControl", "modifyFilter", "chat"}) {
            permissions.put(name, false);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("key", session.documentKey());
        document.put("fileType", extension);
        document.put("title", file.getFileName());
        document.put("url", sourceUrl(session.id()));
        document.put("permissions", permissions);
        Map<String, Object> customization = new LinkedHashMap<>();
        for (String name : new String[]{"autosave", "comments", "chat", "plugins", "macros", "help", "about", "feedback"}) {
            customization.put(name, false);
        }
        customization.put("macrosMode", "disable");
        customization.put("hideRightMenu", true);
        Map<String, Object> editor = new LinkedHashMap<>();
        editor.put("mode", "view");
        editor.put("lang", "zh-CN");
        editor.put("coEditing", Map.of("mode", "strict", "change", false));
        editor.put("customization", customization);
        editor.put("plugins", Map.of("autostart", new String[0], "pluginsData", new String[0]));
        editor.put("user", Map.of("id", session.id(), "name", "读者"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "desktop");
        config.put("documentType", OfficeProtocol.documentType(extension));
        config.put("document", document);
        config.put("editorConfig", editor);
        config.put("width", "100%");
        config.put("height", "100%");
        Map<String, Object> payload = new LinkedHashMap<>(config);
        payload.put("iat", session.createdAt() / 1000);
        payload.put("exp", session.absoluteExpiresAt() / 1000);
        config.put("token", sign(payload, settings.getConfigurationSecret()));
        return config;
    }

    public String sourceUrl(String id) {
        if (!OfficeProtocol.validSessionId(id)) {
            throw new OfficePreviewFailure(404);
        }
        return settings.getSourceBaseUrl().replaceAll("/$", "") + "/internal/office-preview/sessions/" + id + "/source";
    }

    public void requireSourceSignature(String id, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new OfficePreviewFailure(401);
        }
        JsonNode payload = verify(authorization.substring(7), settings.getOutboxSecret());
        if (!"download".equals(payload.path("operation").asText())
                || !sourceUrl(id).equals(payload.path("payload").path("url").asText())) {
            throw new OfficePreviewFailure(401);
        }
    }

    String sign(Map<String, Object> payload, String secret) {
        try {
            String content = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(payload));
            String unsigned = JWT_HEADER + "." + content;
            return unsigned + "." + OfficeCrypto.hmac(secret, unsigned);
        } catch (Exception invalid) {
            throw new OfficePreviewFailure(503);
        }
    }

    private JsonNode verify(String token, String secret) {
        try {
            if (token.length() > 8192 || secret.length() < 32) {
                throw new IllegalArgumentException();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !parts[0].matches("[A-Za-z0-9_-]+") || !parts[1].matches("[A-Za-z0-9_-]+")
                    || !parts[2].matches("[A-Za-z0-9_-]{43}")
                    || !OfficeCrypto.equal(parts[2], OfficeCrypto.hmac(secret, parts[0] + "." + parts[1]))) {
                throw new IllegalArgumentException();
            }
            JsonNode header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText()) || header.has("crit") || header.has("jku") || header.has("jwk")) {
                throw new IllegalArgumentException();
            }
            JsonNode claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            long now = clock.instant().getEpochSecond();
            long issued = claims.path("iat").asLong(0);
            long expires = claims.path("exp").asLong(0);
            if (!claims.isObject() || issued <= 0 || issued > now + 5 || expires <= now
                    || expires <= issued || expires - issued > 300 || issued < now - 300
                    || claims.path("nbf").asLong(0) > now + 5) {
                throw new IllegalArgumentException();
            }
            return claims;
        } catch (Exception invalid) {
            throw new OfficePreviewFailure(401);
        }
    }
}
