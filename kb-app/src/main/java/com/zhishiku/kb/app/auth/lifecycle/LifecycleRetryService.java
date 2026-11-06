package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.common.security.IdentityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 恢复只读取 Inbox，不依赖订阅开关，也不调用成员查询。 */
@Service
public class LifecycleRetryService {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleRetryService.class);
    private final LifecycleAuthorizer auth;
    private final IdentityDepartmentMapper users;
    private final LifecycleInboxMapper inbox;
    private final LifecycleReception reception;
    private final LifecycleReceipts receipts;
    private final IdentityAuditService audit;
    private final TransactionTemplate transaction;

    public record Request(String reason, String requestId) {
        @JsonAnySetter
        public void rejectExtra(String ignored, Object value) {
            throw new IllegalArgumentException("事件重试只接受原因与请求标识");
        }
    }

    public LifecycleRetryService(LifecycleAuthorizer auth, IdentityDepartmentMapper users, LifecycleInboxMapper inbox,
                                LifecycleReception reception, LifecycleReceipts receipts, IdentityAuditService audit,
                                PlatformTransactionManager manager) {
        this.auth = auth;
        this.users = users;
        this.inbox = inbox;
        this.reception = reception;
        this.receipts = receipts;
        this.audit = audit;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(5);
    }

    public LifecycleReception.Result retry(String token, long eventId, Request input) {
        long actor = auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId();
        if (eventId <= 0) {
            throw IdentityException.badRequest("事件标识无效");
        }
        var normalized = new Request(LifecycleSupport.text(input.reason(), "reason", 500),
                LifecycleSupport.text(input.requestId(), "requestId", 128));
        var attempted = new AtomicBoolean();
        try {
            return transaction.execute(status -> {
            users.lockManagement();
            if (auth.require(token, Permission.IDENTITY_LIFECYCLE_MANAGE).userId() != actor) {
                throw LifecycleSupport.stale();
            }
            var replay = receipts.replay(actor, normalized.requestId(), "EVENT_RETRY", Long.toString(eventId), normalized,
                    LifecycleReception.Result.class);
            if (replay != null) {
                return replay;
            }
            attempted.set(true);
            var result = reception.reprocessWithinTransaction(eventId);
            audit.successWithReason(AuditScope.LIFECYCLE_EVENT_RETRY,
                    LifecycleSupport.digest(Map.of("actor", actor, "request", normalized.requestId())), actor, eventId,
                    "retry_event", "{}", LifecycleSupport.json(result), normalized.reason());
            receipts.save(actor, normalized.requestId(), "EVENT_RETRY", Long.toString(eventId), normalized, result);
            return result;
            });
        } catch (RuntimeException failure) {
            if (attempted.get()) {
                recordFailure(eventId);
            }
            throw failure;
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void recover() {
        try {
            var events = inbox.dueEvents(Instant.now());
            for (long eventId : events.stream().limit(4).toList()) {
                try {
                    transaction.executeWithoutResult(status -> reception.reprocessWithinTransaction(eventId));
                } catch (RuntimeException failed) {
                    recordFailure(eventId);
                }
            }
        } catch (RuntimeException failed) {
            LOG.warn("生命周期事件恢复暂不可用");
        }
    }

    private void recordFailure(long eventId) {
        try {
            transaction.executeWithoutResult(status -> {
                users.lockManagement();
                inbox.recordRetryFailure(eventId);
            });
        } catch (RuntimeException failed) {
            LOG.warn("生命周期事件重试状态暂不可用");
        }
    }
}
