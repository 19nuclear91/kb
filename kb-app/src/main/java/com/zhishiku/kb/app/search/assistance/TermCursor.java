package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhishiku.kb.app.search.term.TermException;
import com.zhishiku.kb.app.search.term.TermInput;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;

/** 管理目录的签名修订游标；当前资格由每页读取服务独立重核。 */
@Component
public final class TermCursor {
    private static final Set<String> FIELDS = Set.of("purpose", "revision", "lastId", "digest");
    private final SearchContextKeys keys;
    private final SignedSearchContext envelopes;

    /**
     * 一页已提交的管理条件，不包含任何客户端授权声明。
     * @param userId 当前有效用户
     * @param purpose 术语、错词或共享含义目录
     * @param targetTermId 共享含义的必要目标，普通目录为 null
     * @param nameQuery 名称查询或共享表达，规范化后仅保留精确比较键
     * @param enabled 普通目录的明确状态条件，共享含义固定为 true
     * @param limit 已提交的页面数量，1～100
     */
    public record Conditions(long userId, SignedSearchContext.Purpose purpose, Long targetTermId,
                             String nameQuery, Boolean enabled, int limit) {
        public Conditions {
            if (userId <= 0 || purpose == null || purpose == SignedSearchContext.Purpose.SEARCH_SELECTION || limit < 1 || limit > 100) {
                throw ContextEncoding.invalid();
            }
            try {
                String normalized = nameQuery == null ? "" : TermInput.text(nameQuery, 64, true);
                nameQuery = normalized.isEmpty() ? null : TermInput.key(normalized);
            } catch (TermException invalid) {
                throw ContextEncoding.invalid();
            }
            if (purpose == SignedSearchContext.Purpose.TERM_MEANINGS) {
                if (targetTermId == null || targetTermId <= 0 || nameQuery == null || !Boolean.TRUE.equals(enabled)) {
                    throw ContextEncoding.invalid();
                }
            } else if (targetTermId != null) {
                throw ContextEncoding.invalid();
            }
        }

        @Override
        public String toString() {
            return "TermCursorConditions[redacted]";
        }
    }

    /**
     * 已核验的后续页位置，不包含名称或筛选文本。
     * @param lastId 最后一个稳定实体身份
     * @param revision 游标绑定的当前修订
     * @param keyId 实际验证密钥标识
     */
    public record Position(long lastId, long revision, String keyId) {
        @Override
        public String toString() {
            return "TermCursorPosition[redacted]";
        }
    }

    public TermCursor(SearchContextKeys keys, SignedSearchContext envelopes) {
        this.keys = keys;
        this.envelopes = envelopes;
    }

    public String issue(Conditions conditions, long revision, long lastId) {
        if (revision < 0 || lastId <= 0) {
            throw ContextEncoding.unavailable();
        }
        String keyId = keys.currentKeyId();
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("revision", revision).put("lastId", lastId)
                .put("digest", Base64.getUrlEncoder().withoutPadding().encodeToString(digest(keyId, conditions)));
        return envelopes.seal(conditions.purpose(), payload);
    }

    public Position read(String token, Conditions conditions, long currentRevision) {
        keys.currentKeyId();
        if (currentRevision < 0) {
            throw ContextEncoding.unavailable();
        }
        if (token == null) {
            return null;
        }
        var opened = envelopes.open(token, conditions.purpose());
        ObjectNode payload = opened.payload();
        payload.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw ContextEncoding.invalid();
            }
        });
        if (payload.size() != FIELDS.size()) {
            throw ContextEncoding.invalid();
        }
        long revision = number(payload.get("revision"), 0);
        long id = number(payload.get("lastId"), 1);
        JsonNode digest = payload.get("digest");
        if (digest == null || !digest.isTextual()) {
            throw ContextEncoding.invalid();
        }
        byte[] supplied = SignedSearchContext.decode(digest.textValue(), 32);
        if (supplied.length != 32 || !MessageDigest.isEqual(supplied, digest(opened.keyId(), conditions))) {
            throw ContextEncoding.invalid();
        }
        if (revision != currentRevision) {
            throw new SearchContextFailure(SearchContextFailure.Kind.DICTIONARY_CHANGED);
        }
        return new Position(id, revision, opened.keyId());
    }

    private byte[] digest(String keyId, Conditions conditions) {
        byte[] input = new ContextEncoding().number(conditions.userId()).text(conditions.purpose().name())
                .optionalId(conditions.targetTermId()).text(conditions.nameQuery())
                .text(conditions.enabled() == null ? null : conditions.enabled().toString()).number(conditions.limit()).bytes();
        return ContextEncoding.mac(keys.verificationKey(keyId), "term-directory-binding-v1", input);
    }

    private static long number(JsonNode value, long minimum) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < minimum) {
            throw ContextEncoding.invalid();
        }
        return value.longValue();
    }
}
