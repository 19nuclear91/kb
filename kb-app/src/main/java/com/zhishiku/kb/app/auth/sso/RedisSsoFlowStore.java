package com.zhishiku.kb.app.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 单一 Redis hash tag 使相关键处于同一 slot；脚本不调用外部资源。
 */
@Component
public class RedisSsoFlowStore implements SsoFlowStore {
    private static final String PREFIX = "kb:{sso}:";
    private static final DefaultRedisScript<String> CREATE = script("""
            local f = cjson.decode(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', ARGV[2])
            local rate = redis.call('INCR', KEYS[5])
            if rate == 1 then redis.call('PEXPIRE', KEYS[5], 60000) end
            if rate > tonumber(ARGV[4]) or redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[5]) then return 'LIMIT' end
            redis.call('SET', KEYS[1], f.flowId, 'PX', ARGV[3])
            redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3])
            redis.call('SET', KEYS[3], f.flowId, 'PX', ARGV[3])
            redis.call('ZADD', KEYS[4], f.expiresAt, f.flowId)
            redis.call('PEXPIRE', KEYS[4], 660000)
            return 'OK'
            """);
    private static final DefaultRedisScript<String> CLAIM = script("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 'INVALID' end
            local f = cjson.decode(raw)
            if f.connectionId ~= ARGV[1] or f.browserDigest ~= ARGV[2] or f.stateDigest ~= ARGV[3]
              or f.stage ~= 'CREATED' or f.expiresAt <= tonumber(ARGV[4])
              or redis.call('GET', KEYS[2]) ~= f.flowId or redis.call('GET', KEYS[3]) ~= f.flowId then return 'INVALID' end
            f.stage = 'PROCESSING'
            redis.call('SET', KEYS[1], cjson.encode(f), 'KEEPTTL')
            redis.call('DEL', KEYS[3])
            return cjson.encode(f)
            """);
    private static final DefaultRedisScript<String> READY = script("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 'INVALID' end
            local old = cjson.decode(raw)
            local f = cjson.decode(ARGV[1])
            if old.stage ~= 'PROCESSING' or old.flowId ~= f.flowId or old.intent ~= f.intent
              or old.fingerprint ~= f.fingerprint or old.browserDigest ~= f.browserDigest
              or old.expiresAt <= tonumber(ARGV[2]) or f.expiresAt > old.expiresAt
              or f.expiresAt <= tonumber(ARGV[2]) or redis.call('GET', KEYS[2]) ~= f.flowId then return 'INVALID' end
            local ttl = f.expiresAt - tonumber(ARGV[2])
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ttl)
            redis.call('PEXPIRE', KEYS[2], ttl)
            redis.call('ZADD', KEYS[3], f.expiresAt, f.flowId)
            return 'OK'
            """);
    private static final DefaultRedisScript<String> CONSUME = script("""
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 'INVALID' end
            local f = cjson.decode(raw)
            if f.stage ~= 'RESULT_READY' or f.expiresAt <= tonumber(ARGV[1]) or f.resultDigest ~= ARGV[2]
              or f.browserDigest ~= ARGV[3] or f.fingerprint ~= ARGV[4] or f.intent ~= ARGV[5]
              or redis.call('GET', KEYS[2]) ~= f.flowId then return 'INVALID' end
            redis.call('DEL', KEYS[1], KEYS[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[1])
            redis.call('ZREM', KEYS[3], f.flowId)
            return raw
            """);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisSsoFlowStore(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void create(SsoFlow f, int startsPerMinute, int maxActive) {
        guarded(() -> {
            String result = redis.execute(CREATE, List.of(currentKey(f.getBrowserDigest()), flowKey(f.getFlowId()),
                            stateKey(f.getStateDigest()), activeKey(f.getConnectionId()),
                            PREFIX + "rate:" + f.getBrowserDigest()),
                    json.writeValueAsString(f), Long.toString(f.getCreatedAt()),
                    Long.toString(f.getExpiresAt() - f.getCreatedAt()),
                    Integer.toString(startsPerMinute), Integer.toString(maxActive));
            if ("LIMIT".equals(result)) throw new SsoException(429, "RATE_LIMITED");
            if (!"OK".equals(result)) throw SsoException.storage();
            return null;
        });
    }

    @Override
    public SsoFlow claim(String connection, String browser, String state, long now) {
        return guarded(() -> {
            String id = redis.opsForValue().get(stateKey(state));
            if (id == null || !id.matches("[a-f0-9-]{36}")) throw SsoException.invalid();
            return decode(redis.execute(CLAIM, List.of(flowKey(id), currentKey(browser), stateKey(state)),
                    connection, browser, state, Long.toString(now)));
        });
    }

    @Override
    public void ready(SsoFlow f, long now) {
        guarded(() -> {
            String result = redis.execute(READY, List.of(flowKey(f.getFlowId()), currentKey(f.getBrowserDigest()),
                    activeKey(f.getConnectionId())), json.writeValueAsString(f), Long.toString(now));
            if (!"OK".equals(result)) throw SsoException.invalid();
            return null;
        });
    }

    @Override
    public SsoFlow current(String browser, long now) {
        return guarded(() -> {
            String id = redis.opsForValue().get(currentKey(browser));
            if (id == null || !id.matches("[a-f0-9-]{36}")) throw SsoException.invalid();
            SsoFlow f = decode(redis.opsForValue().get(flowKey(id)));
            if (f.getExpiresAt() <= now || !browser.equals(f.getBrowserDigest()) || !"RESULT_READY".equals(f.getStage()))
                throw SsoException.invalid();
            return f;
        });
    }

    @Override
    public SsoFlow consume(SsoFlow f, String result, long now) {
        return guarded(() -> decode(redis.execute(CONSUME, List.of(flowKey(f.getFlowId()),
                currentKey(f.getBrowserDigest()),
                activeKey(f.getConnectionId())), Long.toString(now), result, f.getBrowserDigest(), f.getFingerprint()
                , f.getIntent())));
    }

    private SsoFlow decode(String raw) throws Exception {
        if (raw == null || raw.equals("INVALID")) throw SsoException.invalid();
        return json.readValue(raw, SsoFlow.class);
    }

    private static String currentKey(String browser) {
        return PREFIX + "browser:" + browser;
    }

    private static String flowKey(String id) {
        return PREFIX + "flow:" + id;
    }

    private static String stateKey(String state) {
        return PREFIX + "state:" + state;
    }

    private static String activeKey(String connection) {
        return PREFIX + "active:" + connection;
    }

    private static DefaultRedisScript<String> script(String text) {
        return new DefaultRedisScript<>(text, String.class);
    }

    private static <T> T guarded(StorageCall<T> call) {
        try {
            return call.run();
        } catch (SsoException safe) {
            throw safe;
        } catch (Exception unavailable) {
            throw SsoException.storage();
        }
    }

    @FunctionalInterface
    private interface StorageCall<T> {
        T run() throws Exception;
    }
}
