package com.zhishiku.kb.app.search.assistance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zhishiku.kb.app.search.model.SearchFilters;
import com.zhishiku.kb.app.search.model.SearchInput;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** 无状态建议上下文；原文、身份和条件仅进入带密钥摘要，不进入载荷或历史账本。 */
@Component
public final class SearchSelectionContext {
    public static final long LIFETIME_SECONDS = 600;
    private static final Set<String> FIELDS = Set.of("purpose", "revision", "issuedSeconds", "issuedNanos", "expiresSeconds", "expiresNanos", "digest");
    private final SearchContextKeys keys;
    private final SignedSearchContext envelopes;

    /**
     * 本次已核验身份与提交条件，限词典阶段内使用，不保存在服务或查询计划中。
     * @param userId 服务端有效用户
     * @param authEpoch 持久认证代次
     * @param token 本次已核验令牌，阶段结束后释放引用
     * @param originalQuery 精确原始输入，不作 NFC 或大小写规范化
     * @param filters 完整规范化文档筛选
     * @param candidates 最终候选数量
     * @param topK 最终结果数量
     */
    public record Binding(long userId, long authEpoch, String token, String originalQuery,
                          SearchFilters filters, int candidates, int topK) {
        public Binding {
            if (userId <= 0 || authEpoch <= 0 || token == null || token.isEmpty()) {
                throw ContextEncoding.invalid();
            }
            SearchInput.validate(originalQuery, candidates, topK, Integer.MAX_VALUE);
            filters = filters == null ? SearchFilters.empty() : filters;
        }

        @Override
        public String toString() {
            return "SearchSelectionBinding[redacted]";
        }
    }

    /**
     * 已绑定当前条件且未过期的凭据元数据，不包含输入或令牌。
     * @param keyId 验证使用的受信 keyId
     * @param revision 本次一致词典修订
     * @param expiresAt 数据库时间口径的过期时刻
     */
    public record Verified(String keyId, long revision, Instant expiresAt) {
        @Override
        public String toString() {
            return "VerifiedSearchSelectionContext[redacted]";
        }
    }

    public SearchSelectionContext(SearchContextKeys keys, SignedSearchContext envelopes) {
        this.keys = keys;
        this.envelopes = envelopes;
    }

    /** 至多两个受信密钥的用途隔离绑定摘要，不保存令牌、原文或筛选对象。 */
    public static final class Prepared {
        private final Map<String, byte[]> digests;

        private Prepared(Map<String, byte[]> digests) {
            this.digests = Map.copyOf(digests);
        }

        private byte[] digest(String keyId) {
            byte[] digest = digests.get(keyId);
            if (digest == null) {
                throw ContextEncoding.invalid();
            }
            return digest;
        }

        @Override
        public String toString() {
            return "PreparedSelectionBinding[redacted]";
        }
    }

    public Prepared prepare(Binding binding) {
        Map<String, byte[]> digests = new LinkedHashMap<>();
        for (String keyId : keys.trustedKeyIds()) {
            digests.put(keyId, bindingDigest(keyId, binding));
        }
        return new Prepared(digests);
    }

    public String issue(Binding binding, long revision, Instant databaseTime) {
        return issuePrepared(prepare(binding), revision, databaseTime);
    }

    public String issuePrepared(Prepared binding, long revision, Instant databaseTime) {
        if (revision < 0 || databaseTime == null) {
            throw ContextEncoding.unavailable();
        }
        String keyId = keys.currentKeyId();
        Instant expiry;
        try {
            expiry = databaseTime.plusSeconds(LIFETIME_SECONDS);
        } catch (DateTimeException overflow) {
            throw ContextEncoding.unavailable();
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("revision", revision)
                .put("issuedSeconds", databaseTime.getEpochSecond()).put("issuedNanos", databaseTime.getNano())
                .put("expiresSeconds", expiry.getEpochSecond()).put("expiresNanos", expiry.getNano())
                .put("digest", Base64.getUrlEncoder().withoutPadding().encodeToString(binding.digest(keyId)));
        return envelopes.seal(SignedSearchContext.Purpose.SEARCH_SELECTION, payload);
    }

    public Verified verify(String token, Binding binding, long currentRevision, Instant databaseTime) {
        return verifyPrepared(token, prepare(binding), currentRevision, databaseTime);
    }

    public Verified verifyPrepared(String token, Prepared binding, long currentRevision, Instant databaseTime) {
        var opened = envelopes.open(token, SignedSearchContext.Purpose.SEARCH_SELECTION);
        ObjectNode payload = opened.payload();
        payload.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw ContextEncoding.invalid();
            }
        });
        if (payload.size() != FIELDS.size()) {
            throw ContextEncoding.invalid();
        }
        long revision = number(payload.get("revision"), 0, Long.MAX_VALUE);
        Instant issued = instant(payload, "issued");
        Instant expires = instant(payload, "expires");
        try {
            if (!issued.plusSeconds(LIFETIME_SECONDS).equals(expires)) {
                throw ContextEncoding.invalid();
            }
        } catch (DateTimeException invalidTime) {
            throw ContextEncoding.invalid();
        }
        JsonNode digest = payload.get("digest");
        if (digest == null || !digest.isTextual()) {
            throw ContextEncoding.invalid();
        }
        byte[] supplied = SignedSearchContext.decode(digest.textValue(), 32);
        if (supplied.length != 32 || !MessageDigest.isEqual(supplied, binding.digest(opened.keyId()))) {
            throw ContextEncoding.invalid();
        }
        if (currentRevision < 0 || databaseTime == null) {
            throw ContextEncoding.unavailable();
        }
        if (revision != currentRevision || !databaseTime.isBefore(expires)) {
            throw new SearchContextFailure(SearchContextFailure.Kind.ASSISTANCE_STALE);
        }
        return new Verified(opened.keyId(), revision, expires);
    }

    private byte[] bindingDigest(String keyId, Binding binding) {
        var key = keys.verificationKey(keyId);
        byte[] sessionFingerprint = ContextEncoding.mac(key, "search-assistance-session-v1", ContextEncoding.utf8(binding.token()));
        SearchFilters filters = binding.filters();
        var input = new ContextEncoding().number(binding.userId()).number(binding.authEpoch()).data(sessionFingerprint)
                .text(binding.originalQuery()).optionalId(filters.spaceId()).optionalId(filters.categoryId()).flag(filters.uncategorized())
                .number(filters.tagIds().size());
        for (Long tag : filters.tagIds()) {
            input.number(tag);
        }
        input.text(filters.fileType() == null ? null : filters.fileType().name()).time(filters.updatedFrom()).time(filters.updatedTo())
                .number(binding.candidates()).number(binding.topK());
        return ContextEncoding.mac(key, "search-selection-binding-v1", input.bytes());
    }

    private static Instant instant(ObjectNode payload, String prefix) {
        long seconds = number(payload.get(prefix + "Seconds"), Long.MIN_VALUE, Long.MAX_VALUE);
        long nanos = number(payload.get(prefix + "Nanos"), 0, 999999999);
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (DateTimeException invalidTime) {
            throw ContextEncoding.invalid();
        }
    }

    private static long number(JsonNode value, long min, long max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < min || value.longValue() > max) {
            throw ContextEncoding.invalid();
        }
        return value.longValue();
    }
}
