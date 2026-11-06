package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.common.conversation.RequestSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class LifecycleProofService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final LifecycleMapper mapper;
    private final Clock clock;

    public record Issued(String proof, Instant expiresAt) {
        @Override
        public String toString() {
            return "IssuedProof[redacted]";
        }
    }

    @Autowired
    public LifecycleProofService(LifecycleMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    public LifecycleProofService(LifecycleMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(long actor, long user, String operation, Object binding, Object evidence) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expires = now.plusSeconds(300);
        var proof = new LifecycleProof(RequestSignature.digest(token), actor, user, operation,
                LifecycleSupport.digest(binding), LifecycleSupport.json(evidence), expires, null, now);
        if (mapper.insertProof(proof) != 1) {
            throw new IllegalStateException("核验凭据写入失败");
        }
        return new Issued(token, expires);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T consume(String token, long actor, long user, String operation, Object binding, Class<T> evidenceType) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw LifecycleSupport.stale();
        }
        String hash = RequestSignature.digest(token);
        LifecycleProof proof = mapper.lockProof(hash);
        Instant now = clock.instant();
        if (proof == null || proof.actorUserId() != actor || proof.targetUserId() != user
                || !operation.equals(proof.operation()) || proof.consumedAt() != null || !proof.expiresAt().isAfter(now)
                || !RequestSignature.constantEquals(proof.bindingDigest(), LifecycleSupport.digest(binding))) {
            throw LifecycleSupport.stale();
        }
        if (mapper.consumeProof(hash, now) != 1) {
            throw LifecycleSupport.stale();
        }
        return LifecycleSupport.read(proof.evidence(), evidenceType);
    }
}
