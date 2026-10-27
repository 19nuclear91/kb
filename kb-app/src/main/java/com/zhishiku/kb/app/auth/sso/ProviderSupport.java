package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 只共享传输与严格字段读取，不共享成员资格推断。
 */
abstract class ProviderSupport implements ExternalLoginProvider {
    protected final SsoSettings settings;
    protected final SsoHttp http;
    protected final SsoAppTokens tokens;

    ProviderSupport(SsoSettings settings, SsoHttp http, SsoAppTokens tokens) {
        this.settings = settings;
        this.http = http;
        this.tokens = tokens;
    }

    protected static String enc(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    protected static String text(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 8192)
            throw SsoException.unavailable();
        return value.textValue();
    }

    protected static long number(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isIntegralNumber()) throw SsoException.unavailable();
        return value.longValue();
    }

    protected static boolean flag(JsonNode node, String key) {
        var value = node.get(key);
        if (value == null || !value.isBoolean()) throw SsoException.unavailable();
        return value.booleanValue();
    }

    protected static void check(JsonNode node, String key, Set<Long> denied, Set<Long> rejected) {
        long code = number(node, key);
        if (code == 0) return;
        if (denied.contains(code)) throw SsoException.denied();
        if (rejected.contains(code)) throw new SsoException(400, "CREDENTIAL_REJECTED");
        throw SsoException.unavailable();
    }

    protected static String code(Map<String, String> params, String codeKey) {
        if (params.containsKey("error")) throw new SsoException(403, "CREDENTIAL_REJECTED");
        String code = params.get(codeKey);
        if (code == null || code.isBlank() || code.length() > 2048 || code.codePoints().anyMatch(Character::isISOControl))
            throw SsoException.invalid();
        return code;
    }

    protected SsoProperties.Connection connection() {
        return settings.connection(platform());
    }

    protected VerifiedExternalIdentity identity(String subject) {
        var c = connection();
        return new VerifiedExternalIdentity(platform(), c.getTargetEnterpriseId(), SsoScope.application(platform(), c),
                SsoScope.kind(platform()), subject);
    }
}
