package com.zhishiku.kb.common.suggestion;

import com.zhishiku.kb.common.conversation.RequestSignature;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

/** 内部调用绑定执行身份、路径、原始载荷及起始会话。 */
public final class SuggestionProtocol {
    public static final String ROOT = "/internal/metadata-suggestions/";
    public static final int MATERIAL_LIMIT = 4 * 1024 * 1024;
    public static final int RESULT_LIMIT = 128 * 1024;
    public static final Set<String> REASONS = Set.of("INPUT_TOO_LARGE", "SNAPSHOT_EMPTY", "SNAPSHOT_MISSING",
            "SNAPSHOT_TRUNCATED", "SOURCE_CHANGED", "TREE_VERSION_CONFLICT", "METADATA_VERSION_CONFLICT",
            "FORBIDDEN", "NOT_FOUND", "INVALID_MODEL_OUTPUT", "OUTPUT_TOO_LARGE", "MODEL_UNAVAILABLE", "QUOTA_REJECTED",
            "CANCELLED", "TIMEOUT", "UNAVAILABLE");

    private SuggestionProtocol() {
    }

    public record Key(UUID requestId, UUID executionId, long departmentId, long deadlineMillis) {
        public Key {
            if (requestId == null || executionId == null || departmentId <= 0 || deadlineMillis <= 0) {
                throw new IllegalArgumentException("执行身份无效");
            }
        }
    }

    public record Run(Key key, SuggestionData.Material material) {
    }

    public record Action(Key key, long reservation) {
    }

    public record Charge(Key key, long reservation, long charged) {
    }

    public record Outcome(String reason, Long prompt, Long completion, SuggestionData.Result result) {
        public static Outcome failure(String reason) {
            return new Outcome(reason, null, null, null);
        }
    }

    public static void verify(String secret, String caller, String operation, byte[] body, String token,
                              RequestSignature.Headers headers, Clock clock, RequestSignature.NonceStore nonces) {
        requireSecret(secret);
        RequestSignature.verify(secret, caller, "POST", ROOT + operation, body, token, headers, clock, 30, 90, nonces);
    }

    public static void requireSecret(String secret) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException("资料整理内部认证不可用");
        }
    }
}
