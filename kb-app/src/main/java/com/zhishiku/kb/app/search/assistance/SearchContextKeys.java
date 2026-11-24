package com.zhishiku.kb.app.search.assistance;

import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 静态受信密钥集合；从不自动生成实例密钥，安全诊断不包含配置值。 */
@Component
public final class SearchContextKeys {
    public enum State {
        AVAILABLE, MISSING_CURRENT, INVALID_KEY_ID, SHORT_OR_INVALID_SECRET, INVALID_PREVIOUS, DUPLICATE_KEY_ID
    }

    private final State state;
    private final String currentKeyId;
    private final Map<String, SecretKeySpec> keys;

    public SearchContextKeys(SearchAssistanceContextProperties properties) {
        State validation = validate(properties);
        state = validation;
        if (validation != State.AVAILABLE) {
            currentKeyId = null;
            keys = Map.of();
            return;
        }
        currentKeyId = properties.keyId();
        var accepted = new LinkedHashMap<String, SecretKeySpec>();
        accepted.put(properties.keyId(), key(properties.secret()));
        if (present(properties.previousKeyId())) {
            accepted.put(properties.previousKeyId(), key(properties.previousSecret()));
        }
        keys = Map.copyOf(accepted);
    }

    public State state() {
        return state;
    }

    public boolean available() {
        return state == State.AVAILABLE;
    }

    public String currentKeyId() {
        requireAvailable();
        return currentKeyId;
    }

    public SecretKeySpec verificationKey(String keyId) {
        requireAvailable();
        if (!validId(keyId)) {
            throw new SearchContextFailure(SearchContextFailure.Kind.INVALID_INPUT);
        }
        SecretKeySpec key = keys.get(keyId);
        if (key == null) {
            throw new SearchContextFailure(SearchContextFailure.Kind.INVALID_INPUT);
        }
        return key;
    }

    public Set<String> trustedKeyIds() {
        requireAvailable();
        return keys.keySet();
    }

    private void requireAvailable() {
        if (!available()) {
            throw new SearchContextFailure(SearchContextFailure.Kind.ASSISTANCE_UNAVAILABLE);
        }
    }

    private static State validate(SearchAssistanceContextProperties properties) {
        if (properties == null || !present(properties.keyId()) || !present(properties.secret())) {
            return State.MISSING_CURRENT;
        }
        if (!validId(properties.keyId())) {
            return State.INVALID_KEY_ID;
        }
        if (!validSecret(properties.secret())) {
            return State.SHORT_OR_INVALID_SECRET;
        }
        boolean previousId = present(properties.previousKeyId());
        boolean previousSecret = present(properties.previousSecret());
        if (previousId != previousSecret) {
            return State.INVALID_PREVIOUS;
        }
        if (previousId) {
            if (!validId(properties.previousKeyId()) || !validSecret(properties.previousSecret())) {
                return State.INVALID_PREVIOUS;
            }
            if (properties.keyId().equals(properties.previousKeyId())) {
                return State.DUPLICATE_KEY_ID;
            }
        }
        return State.AVAILABLE;
    }

    static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static boolean validSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return false;
        }
        for (int i = 0; i < secret.length(); i++) {
            char c = secret.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= secret.length() || !Character.isLowSurrogate(secret.charAt(i))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static boolean present(String value) {
        return value != null && !value.isEmpty();
    }

    @Override
    public String toString() {
        return "SearchContextKeys[state=" + state + "]";
    }
}
