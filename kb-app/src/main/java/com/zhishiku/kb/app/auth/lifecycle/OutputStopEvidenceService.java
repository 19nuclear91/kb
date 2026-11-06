package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.auth.mapper.OutputStopEvidenceMapper;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.security.AuthenticationSignature;
import com.zhishiku.kb.common.security.OutputStopReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/** 观测只有独立服务权限，不使用已撤销的用户 token，也不能进行任何账号变更。 */
@Service
public class OutputStopEvidenceService {
    private final OutputStopEvidenceMapper mapper;
    private final LifecycleObservations observations;
    private final TransactionTemplate transaction;
    private final String secret;
    private final AuthenticationSignature.NonceStore nonces;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Autowired
    public OutputStopEvidenceService(OutputStopEvidenceMapper mapper, LifecycleObservations observations,
                                     PlatformTransactionManager manager, @Value("${kb.auth.internal.secret:}") String secret,
                                     StringRedisTemplate redis) {
        this(mapper, observations, manager, secret, (caller, nonce, ttl) -> Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent("kb:auth:output-stop:nonce:" + caller + ":" + nonce, "1", Duration.ofSeconds(ttl))), Clock.systemUTC());
    }

    public OutputStopEvidenceService(OutputStopEvidenceMapper mapper, LifecycleObservations observations,
                                     PlatformTransactionManager manager, String secret,
                                     AuthenticationSignature.NonceStore nonces, Clock clock) {
        this.mapper = mapper;
        this.observations = observations;
        this.secret = secret;
        this.nonces = nonces;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(3);
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(128).maxNumberLength(20).build());
    }

    /** 只能在原事务 afterCommit 调用；失败保持未知，不影响已提交的停权和 ACK。 */
    public void committed(long eventId, boolean firstReception) {
        try {
            transaction.executeWithoutResult(status -> {
                if (firstReception) {
                    mapper.receptionCommitted(eventId);
                }
                mapper.barriersCommitted(eventId);
            });
        } catch (RuntimeException unknown) {
            observations.dependencyFailed(LifecycleObservations.Dependency.EVIDENCE);
        }
    }

    public void accept(String method, String path, byte[] body, AuthenticationSignature.Headers headers) {
        if (!AuthenticationSignature.configured(secret)) {
            throw ApiException.unavailable("内部观测暂不可用");
        }
        try {
            AuthenticationSignature.verifyStop(secret, method, path, body, headers, clock, nonces);
        } catch (SecurityException invalid) {
            throw ApiException.forbidden("内部观测请求无效");
        } catch (RuntimeException unavailable) {
            throw ApiException.unavailable("内部观测暂不可用");
        }
        OutputStopReport report;
        try {
            report = json.readValue(body, OutputStopReport.class);
            if (report == null) {
                throw new IllegalArgumentException();
            }
        } catch (Exception invalid) {
            throw ApiException.badRequest("输出停止证据无效");
        }
        String digest = RequestSignature.sha256(body);
        transaction.executeWithoutResult(status -> {
            int inserted = mapper.stopped(report, digest);
            if (inserted != 1 && !digest.equals(mapper.binding(report.executionId()))) {
                throw ApiException.conflict("输出停止证据绑定冲突");
            }
        });
    }
}
