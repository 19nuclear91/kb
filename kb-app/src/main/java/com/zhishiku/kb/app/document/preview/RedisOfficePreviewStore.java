package com.zhishiku.kb.app.document.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.common.office.OfficeProtocol;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/** 单个 hash tag 保证会话、用户额度与全局额度原子更新，所有索引都有 TTL。 */
@Component
public class RedisOfficePreviewStore implements OfficePreviewStore {
    private static final String PREFIX = "kb:{office-preview}:";
    private static final DefaultRedisScript<String> CREATE = script("""
            local now = tonumber(ARGV[2])
            local s = cjson.decode(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            if redis.call('EXISTS', KEYS[1]) == 1 then return 'INVALID' end
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[3]) or
               redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[4]) then return 'LIMIT' end
            local ttl = s.expiresAt - now
            if ttl <= 0 or s.expiresAt > s.absoluteExpiresAt or s.state ~= 'PENDING' then return 'INVALID' end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
            redis.call('ZADD', KEYS[2], s.expiresAt, s.id)
            redis.call('ZADD', KEYS[3], s.expiresAt, s.id)
            redis.call('PEXPIRE', KEYS[2], 3720000)
            redis.call('PEXPIRE', KEYS[3], 3720000)
            return 'OK'
            """);
    private static final DefaultRedisScript<String> UPDATE = script("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 'INVALID' end
            local s = cjson.decode(raw)
            local now = tonumber(ARGV[1])
            local expires = math.min(tonumber(ARGV[2]), s.absoluteExpiresAt)
            if s.expiresAt <= now or expires <= now or s.userId ~= ARGV[3] or s.state ~= ARGV[4] then
              return 'INVALID'
            end
            if s.state == 'PENDING' then
              if s.launchDigest ~= ARGV[5] then return 'INVALID' end
              s.state = 'ACTIVE'
              s.launchDigest = ''
              s.cookieDigest = ARGV[6]
            elseif s.cookieDigest ~= ARGV[6] then
              return 'INVALID'
            end
            s.expiresAt = expires
            local updated = cjson.encode(s)
            redis.call('SET', KEYS[1], updated, 'PX', expires - now)
            redis.call('ZADD', KEYS[2], expires, s.id)
            redis.call('ZADD', KEYS[3], expires, s.id)
            redis.call('PEXPIRE', KEYS[2], 3720000)
            redis.call('PEXPIRE', KEYS[3], 3720000)
            return updated
            """);
    private static final DefaultRedisScript<String> CLOSE = script("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 'OK' end
            local s = cjson.decode(raw)
            if s.userId ~= ARGV[1] or s.originalToken ~= ARGV[2] then return 'INVALID' end
            redis.call('DEL', KEYS[1])
            redis.call('ZREM', KEYS[2], s.id)
            redis.call('ZREM', KEYS[3], s.id)
            return 'OK'
            """);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisOfficePreviewStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json.copy();
    }

    @Override
    public void create(OfficePreviewSession session, long now, int maxPerUser, int maxTotal) {
        guarded(() -> {
            String result = redis.execute(CREATE, keys(session), encode(session), Long.toString(now),
                    Integer.toString(maxPerUser), Integer.toString(maxTotal));
            if ("LIMIT".equals(result)) {
                throw new OfficePreviewFailure(429);
            }
            if (!"OK".equals(result)) {
                throw new OfficePreviewFailure(404);
            }
            return null;
        });
    }

    @Override
    public OfficePreviewSession find(String id, long now) {
        if (!OfficeProtocol.validSessionId(id)) {
            throw new OfficePreviewFailure(404);
        }
        return guarded(() -> {
            OfficePreviewSession result = decode(redis.opsForValue().get(PREFIX + "session:" + id));
            if (!id.equals(result.id()) || result.expiresAt() <= now || result.absoluteExpiresAt() <= now) {
                throw new OfficePreviewFailure(404);
            }
            return result;
        });
    }

    @Override
    public OfficePreviewSession activate(OfficePreviewSession pending, String launchDigest, String cookieDigest,
                                         long now, long expiresAt) {
        return update(pending, "PENDING", launchDigest, cookieDigest, now, expiresAt);
    }

    @Override
    public OfficePreviewSession heartbeat(OfficePreviewSession active, long now, long expiresAt) {
        return update(active, "ACTIVE", "", active.cookieDigest(), now, expiresAt);
    }

    @Override
    public void close(OfficePreviewSession session) {
        guarded(() -> {
            String result = redis.execute(CLOSE, keys(session), Long.toString(session.userId()), session.originalToken());
            if (!"OK".equals(result)) {
                throw new OfficePreviewFailure(404);
            }
            return null;
        });
    }

    private OfficePreviewSession update(OfficePreviewSession session, String state, String launch, String cookie,
                                         long now, long expiresAt) {
        return guarded(() -> decode(redis.execute(UPDATE, keys(session), Long.toString(now), Long.toString(expiresAt),
                Long.toString(session.userId()), state, launch, cookie)));
    }

    private String encode(OfficePreviewSession session) throws Exception {
        // Lua cjson 使用双精度数字；业务 ID 必须作为字符串保存，避免超过 2^53 后改绑。
        var value = json.valueToTree(session);
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) value;
        object.put("userId", Long.toString(session.userId()));
        object.put("documentId", Long.toString(session.documentId()));
        object.put("versionId", Long.toString(session.versionId()));
        return json.writeValueAsString(object);
    }

    private OfficePreviewSession decode(String raw) throws Exception {
        if (raw == null || "INVALID".equals(raw)) {
            throw new OfficePreviewFailure(404);
        }
        return json.readValue(raw, OfficePreviewSession.class);
    }

    private static List<String> keys(OfficePreviewSession session) {
        return List.of(PREFIX + "session:" + session.id(), PREFIX + "user:" + session.userId(), PREFIX + "all");
    }

    private static DefaultRedisScript<String> script(String text) {
        return new DefaultRedisScript<>(text, String.class);
    }

    private static <T> T guarded(StorageCall<T> call) {
        try {
            return call.run();
        } catch (OfficePreviewFailure safe) {
            throw safe;
        } catch (Exception unavailable) {
            throw new OfficePreviewFailure(503);
        }
    }

    @FunctionalInterface
    private interface StorageCall<T> {
        T run() throws Exception;
    }
}
