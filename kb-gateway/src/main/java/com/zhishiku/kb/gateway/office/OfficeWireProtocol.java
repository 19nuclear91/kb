package com.zhishiku.kb.gateway.office;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.common.office.OfficeCrypto;
import com.zhishiku.kb.common.office.OfficeProtocol;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Engine.IO 4 / Socket.IO 的有限只读子集；未知消息不透传。 */
public final class OfficeWireProtocol {
    private static final Set<String> SERVER_MESSAGES = Set.of("license", "auth", "authChanges", "documentOpen", "connectState",
            "session", "refreshToken", "meta", "warning", "error", "drop", "disconnectReason");
    private final ObjectMapper json;
    private final OfficeGatewaySettings settings;

    public OfficeWireProtocol(ObjectMapper json, OfficeGatewaySettings settings) {
        this.json = json;
        this.settings = settings;
    }

    public Map<String, String> query(String raw, boolean socket, String key) {
        Map<String, String> result = new HashMap<>();
        if (raw != null) {
            if (raw.length() > 2048) {
                throw new OfficeProxyFailure(404);
            }
            for (String part : raw.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2 || !pair[0].matches("[A-Za-z0-9_]+") || pair[1].length() > 256
                        || result.putIfAbsent(pair[0], queryValue(pair[0], pair[1])) != null) {
                    throw new OfficeProxyFailure(404);
                }
            }
        }
        Set<String> allowed = socket ? Set.of("EIO", "transport", "sid", "t", "shardkey", "WOPISrc")
                : Set.of("md5", "expires", "shardkey", "v", "ver", "lang", "theme", "type", "placement", "_dc",
                        "customer", "parentOrigin", "frameEditorId", "mode", "isForm", "fileType");
        if (!allowed.containsAll(result.keySet()) || result.containsKey("WOPISrc")
                || (result.containsKey("shardkey") && !key.equals(result.get("shardkey")))) {
            throw new OfficeProxyFailure(404);
        }
        if (socket && (!"4".equals(result.get("EIO")) || !Set.of("websocket", "polling").contains(result.getOrDefault("transport", ""))
                || (result.containsKey("sid") && !result.get("sid").matches("[A-Za-z0-9_-]{1,128}")))) {
            throw new OfficeProxyFailure(404);
        }
        return result;
    }

    private String queryValue(String name, String value) {
        if (name.equals("parentOrigin")) {
            String decoded;
            try {
                decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (RuntimeException invalid) {
                throw new OfficeProxyFailure(404);
            }
            if (!settings.getPreviewOrigin().equals(decoded)) {
                throw new OfficeProxyFailure(404);
            }
            return decoded;
        }
        if (!value.matches("[A-Za-z0-9_.~-]{0,256}") || (name.equals("frameEditorId") && !value.equals("viewer"))
                || (name.equals("mode") && !value.equals("view")) || (name.equals("isForm") && !value.equals("false"))
                || (name.equals("fileType") && !OfficeProtocol.FORMATS.contains(value))) {
            throw new OfficeProxyFailure(404);
        }
        return value;
    }

    public String frame(String frame, boolean fromBrowser, JsonNode grant, String sessionId) {
        if (frame == null || frame.isEmpty() || frame.getBytes(StandardCharsets.UTF_8).length > settings.getMaxMessageBytes()) {
            throw new OfficeProxyFailure(415);
        }
        try {
            if (Set.of("1", "2", "3", "2probe", "3probe", "5", "6", "41").contains(frame)) {
                return frame;
            }
            if (!fromBrowser && frame.startsWith("0")) {
                JsonNode open = json.readTree(frame.substring(1));
                if (!open.path("sid").asText().matches("[A-Za-z0-9_-]{1,128}")) {
                    throw new OfficeProxyFailure(415);
                }
                return frame;
            }
            if (frame.startsWith("40")) {
                JsonNode connect = json.readTree(frame.substring(2));
                if (fromBrowser) {
                    authenticate(connect.path("data"), connect.path("token").asText(), grant);
                } else if (!connect.path("sid").asText().matches("[A-Za-z0-9_-]{1,128}")) {
                    throw new OfficeProxyFailure(415);
                }
                return frame;
            }
            if (!frame.startsWith("42")) {
                throw new OfficeProxyFailure(415);
            }
            JsonNode event = json.readTree(frame.substring(2));
            if (!event.isArray() || event.size() != 2 || !"message".equals(event.path(0).asText()) || !event.path(1).isObject()) {
                throw new OfficeProxyFailure(415);
            }
            JsonNode message = event.path(1);
            String type = message.path("type").asText();
            if (fromBrowser) {
                if (!OfficeProtocol.CLIENT_MESSAGES.contains(type)) {
                    throw new OfficeProxyFailure(415);
                }
                if (type.equals("auth")) {
                    authenticate(message, message.path("jwtOpen").asText(), grant);
                } else if (type.equals("openDocument")) {
                    command(message.path("message"), grant);
                }
                rejectForeignTargets(message, grant);
                return frame;
            }
            if (!SERVER_MESSAGES.contains(type)) {
                throw new OfficeProxyFailure(415);
            }
            // 固定版本在认证前发送许可信息；它是只读初始化消息，不开放客户端同名命令。
            if (type.equals("license") && (!message.path("license").isObject()
                    || !message.path("license").path("type").isIntegralNumber())) {
                throw new OfficeProxyFailure(415);
            }
            if (Set.of("warning", "error", "drop", "disconnectReason").contains(type)) {
                throw new OfficeProxyFailure(503);
            }
            return "42" + json.writeValueAsString(sanitize(event, grant.path("documentKey").asText(), sessionId, 0));
        } catch (OfficeProxyFailure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw new OfficeProxyFailure(415);
        }
    }

    private void authenticate(JsonNode data, String token, JsonNode grant) {
        if (!data.isObject() || !OfficeCrypto.equal(token, grant.path("configurationToken").asText())
                || !grant.path("documentKey").asText().equals(data.path("docid").asText())
                || !"view".equals(data.path("mode").asText()) || data.path("encrypted").asBoolean(false)
                || data.path("documentCallbackUrl").asText("").length() > 0) {
            throw new OfficeProxyFailure(415);
        }
        JsonNode permissions = data.path("permissions");
        for (String field : Set.of("edit", "download", "print", "comment", "review", "fillForms", "chat")) {
            if (permissions.path(field).asBoolean(false)) {
                throw new OfficeProxyFailure(415);
            }
        }
        if (data.hasNonNull("openCmd")) {
            command(data.path("openCmd"), grant);
        }
        rejectForeignTargets(data, grant);
    }

    private void command(JsonNode command, JsonNode grant) {
        String operation = command.path("c").asText();
        if (!command.isObject() || !OfficeProtocol.OPEN_COMMANDS.contains(operation)) {
            throw new OfficeProxyFailure(415);
        }
        if (command.hasNonNull("id") && !grant.path("documentKey").asText().equals(command.path("id").asText())) {
            throw new OfficeProxyFailure(415);
        }
        rejectForeignTargets(command, grant);
        if (operation.equals("pathurl")) {
            readPath(command.path("data").asText());
        } else if (operation.equals("pathurls")) {
            JsonNode paths = command.path("data");
            if (!paths.isArray() || paths.size() > 256) {
                throw new OfficeProxyFailure(415);
            }
            paths.forEach(path -> readPath(path.asText()));
        }
    }

    private void readPath(String path) {
        if (!path.equals("Editor.bin") && !path.equals("Editor.json")
                && !path.matches("media/[A-Za-z0-9_-]+\\.(png|jpg|jpeg|gif|svg|webp|emf|wmf)")) {
            throw new OfficeProxyFailure(415);
        }
    }

    private void rejectForeignTargets(JsonNode node, JsonNode grant) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                JsonNode value = entry.getValue();
                if (Set.of("docid", "key", "id").contains(name) && value.isTextual()
                        && name.equals("docid") && !value.asText().equals(grant.path("documentKey").asText())) {
                    throw new OfficeProxyFailure(415);
                }
                if ((name.equals("url") && !value.asText().equals(grant.path("sourceUrl").asText()))
                        || ((name.contains("password") || name.equals("callbackurl")) && !value.isNull() && !value.asText().isEmpty())) {
                    throw new OfficeProxyFailure(415);
                }
                rejectForeignTargets(value, grant);
            });
        } else if (node.isArray()) {
            node.forEach(value -> rejectForeignTargets(value, grant));
        }
    }

    private JsonNode sanitize(JsonNode value, String key, String id, int depth) {
        if (depth > 32) {
            throw new OfficeProxyFailure(415);
        }
        if (value.isTextual()) {
            String text = value.asText();
            if (text.startsWith("http:") || text.startsWith("https:") || text.startsWith("//")) {
                URI uri = URI.create(text);
                String prefix = "/s/" + id;
                if (!text.startsWith(settings.getPreviewOrigin() + prefix + "/") || uri.getUserInfo() != null
                        || uri.getFragment() != null || OfficeProtocol.resource("GET", uri.getRawPath().substring(prefix.length()), key)
                        != OfficeProtocol.Resource.CACHE) {
                    return json.nullNode();
                }
                query(uri.getRawQuery(), false, key);
            }
            return value;
        }
        if (value.isObject()) {
            ObjectNode result = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> entries = value.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                JsonNode clean = sanitize(entry.getValue(), key, id, depth + 1);
                if (!clean.isNull()) {
                    result.set(entry.getKey(), clean);
                }
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = json.createArrayNode();
            value.forEach(item -> result.add(sanitize(item, key, id, depth + 1)));
            return result;
        }
        return value;
    }
}
