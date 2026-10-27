package com.zhishiku.kb.app.auth.sso;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * 状态机编排；存储实现负责竞争下的唯一领取。
 */
@Component
public class SsoFlows {
    private final SsoFlowStore store;
    private final SsoSettings settings;
    private final Clock clock;

    @Autowired
    public SsoFlows(SsoFlowStore store, SsoSettings settings) {
        this(store, settings, Clock.systemUTC());
    }

    SsoFlows(SsoFlowStore store, SsoSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    public Started start(String id, String intent, String browser, Long owner, String sessionDigest,
                         String credential) {
        var c = settings.connection(id);
        if (!intent.equals("LOGIN") && !intent.equals("BIND")) throw SsoException.invalid();
        if (intent.equals("BIND") && (owner == null || sessionDigest == null || credential == null))
            throw SsoException.invalid();
        if (intent.equals("LOGIN") && (owner != null || sessionDigest != null || credential != null))
            throw SsoException.invalid();
        String proof = SsoCrypto.proof(browser) ? browser : SsoCrypto.random();
        String state = SsoCrypto.random();
        SsoFlow f = new SsoFlow();
        f.setFlowId(UUID.randomUUID().toString());
        f.setIntent(intent);
        f.setConnectionId(id);
        f.setBrowserDigest(SsoCrypto.digest(proof));
        f.setStateDigest(SsoCrypto.digest(state));
        f.setIdentityScope(SsoScope.provider(id, c));
        f.setFingerprint(SsoScope.fingerprint(id, settings));
        f.setCreatedAt(clock.millis());
        f.setExpiresAt(clock.millis() + settings.properties().getStateTtlSeconds() * 1000L);
        f.setStage("CREATED");
        f.setVerifier(SsoCrypto.random());
        f.setOwnerUserId(owner);
        f.setSessionDigest(sessionDigest);
        f.setCredentialFingerprint(credential);
        store.create(f, settings.properties().getStartsPerMinute(), settings.properties().getMaxActiveFlows());
        return new Started(f, proof, state);
    }

    public SsoFlow claim(String id, String browser, String state) {
        if (!SsoCrypto.proof(browser) || !SsoCrypto.proof(state)) throw SsoException.invalid();
        settings.connection(id);
        var f = store.claim(id, SsoCrypto.digest(browser), SsoCrypto.digest(state), clock.millis());
        check(f);
        return f;
    }

    public ResultSecret ready(SsoFlow f, VerifiedExternalIdentity identity, SsoException failure) {
        check(f);
        if (identity != null) SsoScope.matches(f.getConnectionId(), settings.connection(f.getConnectionId()), identity);
        long now = clock.millis();
        long expires = Math.min(f.getExpiresAt(),
                now + Math.min(60, settings.properties().getResultTtlSeconds()) * 1000L);
        if (expires <= now) throw SsoException.invalid();
        f.setIdentity(identity);
        f.setErrorStatus(failure == null ? 0 : failure.status());
        f.setErrorCode(failure == null ? null : failure.code());
        f.setExpiresAt(expires);
        f.setStage("RESULT_READY");
        f.setVerifier(null);
        String secret = SsoCrypto.random();
        f.setResultDigest(SsoCrypto.digest(secret));
        store.ready(f, now);
        return new ResultSecret(f.getFlowId(), secret, (expires - now + 999) / 1000);
    }

    public SsoFlow current(String browser) {
        if (!SsoCrypto.proof(browser)) throw SsoException.invalid();
        var f = store.current(SsoCrypto.digest(browser), clock.millis());
        check(f);
        return f;
    }

    public SsoFlow consume(SsoFlow f, String secret) {
        if (!SsoCrypto.proof(secret)) throw SsoException.invalid();
        check(f);
        return store.consume(f, SsoCrypto.digest(secret), clock.millis());
    }

    private void check(SsoFlow f) {
        try {
            if (!f.getFingerprint().equals(SsoScope.fingerprint(f.getConnectionId(), settings)))
                throw new SsoException(400, "CONFIG_CHANGED");
        } catch (SsoException changed) {
            throw new SsoException(400, "CONFIG_CHANGED");
        }
    }

    /**
     * 发起结果，仅供控制器选择 Cookie 和供应商授权地址。
     *
     * @param flow    服务端事务
     * @param browser 浏览器随机证明
     * @param state   一次性授权 state
     */
    public record Started(SsoFlow flow, String browser, String state) {
    }

    /**
     * 独立 flow 的短期结果 Cookie 材料。
     *
     * @param flowId     服务端随机事务 ID
     * @param value      随机结果凭证
     * @param ttlSeconds 剩余秒数
     */
    public record ResultSecret(String flowId, String value, long ttlSeconds) {
    }
}
