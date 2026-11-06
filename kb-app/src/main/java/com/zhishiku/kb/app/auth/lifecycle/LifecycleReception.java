package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.common.security.IdentityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;

@Service
public class LifecycleReception implements LifecycleEventConsumer {
    private final IdentityDepartmentMapper users;
    private final LifecycleMapper mappings;
    private final LifecycleInboxMapper inbox;
    private final LifecycleStateService states;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final LifecycleObservations observations;
    private OutputStopEvidenceService evidence;

    @Autowired
    public void setEvidence(OutputStopEvidenceService evidence) {
        this.evidence = evidence;
    }

    public record Result(long eventId, boolean duplicate, boolean conflicted, int restricted, int review, int unmapped) {
    }

    @Autowired
    public LifecycleReception(IdentityDepartmentMapper users, LifecycleMapper mappings, LifecycleInboxMapper inbox,
                               LifecycleStateService states, PlatformTransactionManager manager, LifecycleObservations observations) {
        this(users, mappings, inbox, states, manager, Clock.systemUTC(), observations);
    }

    LifecycleReception(IdentityDepartmentMapper users, LifecycleMapper mappings, LifecycleInboxMapper inbox,
                        LifecycleStateService states, PlatformTransactionManager manager, Clock clock, LifecycleObservations observations) {
        this.users = users;
        this.mappings = mappings;
        this.inbox = inbox;
        this.states = states;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setTimeout(5);
        this.clock = clock;
        this.observations = observations;
    }

    @Override
    public void receive(LifecycleIncomingEvent incoming) {
        // execute 返回前已完成提交；调用方 SDK 只能在这个方法正常返回之后发 SUCCESS。
        try {
            transaction.executeWithoutResult(status -> {
                Result result = receiveInside(incoming);
                afterCommit(() -> {
                    observations.received(result, clock.instant());
                    if (evidence != null && !result.duplicate()) {
                        evidence.committed(result.eventId(), true);
                    }
                });
            });
        } catch (RuntimeException failure) {
            observations.receptionFailed();
            throw failure;
        }
    }

    private Result receiveInside(LifecycleIncomingEvent incoming) {
            users.lockManagement();
            if (incoming.employees().size() > 1000 || (incoming.offboarding() && incoming.employees().isEmpty())) {
                throw LifecycleFailures.unavailable();
            }
            var event = new LifecycleEventRecord(inbox.nextId(), incoming.sourceScope(), incoming.eventId(),
                    incoming.eventType(), incoming.enterpriseId(), incoming.connectionId(), incoming.bornAt(),
                    incoming.digest(), false, clock.instant());
            var registration = new LifecycleEventRegistry(inbox).register(event);
            if (registration.decision() != LifecycleEventRegistry.Decision.NEW) {
                if (registration.decision() == LifecycleEventRegistry.Decision.CONFLICT) {
                    inbox.isolateUnresolvedConflict(registration.event().id());
                }
                return new Result(registration.event().id(), true,
                        registration.decision() == LifecycleEventRegistry.Decision.CONFLICT, 0, 0, 0);
            }
            if (!incoming.offboarding()) {
                return new Result(event.id(), false, false, 0, 0, 0);
            }
            for (String employee : incoming.employees().stream().distinct().sorted().toList()) {
                if (inbox.insertEmployee(event.id(), employee) != 1) {
                    throw new IllegalStateException("逐员工处置持久化失败");
                }
            }
            return process(event, null, true);
    }

    /** 管理重试及映射登记使用原持久事实；调用者同事务鉴权、检查回执并持有管理锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result reprocessWithinTransaction(long eventId) {
        return original(eventId, null);
    }

    /** 补登记只处理本次已锁定员工，不能在持有其用户锁时再取得其他人的映射锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result reprocessEmployeeWithinTransaction(long eventId, String employeeId) {
        return original(eventId, employeeId);
    }

    private Result original(long eventId, String employeeId) {
        users.lockManagement();
        var event = inbox.findEventById(eventId);
        if (event == null) {
            throw IdentityException.notFound("事件不存在");
        }
        if (event.conflicted() || !"user_leave_org".equals(event.eventType())) {
            if (event.conflicted()) {
                inbox.isolateUnresolvedConflict(event.id());
            }
            return new Result(event.id(), true, event.conflicted(), 0, 0, 0);
        }
        Result result = process(event, employeeId, false);
        afterCommit(() -> {
            observations.applied(result);
            if (evidence != null) {
                evidence.committed(result.eventId(), false);
            }
        });
        return result;
    }

    private Result process(LifecycleEventRecord event, String employeeId, boolean initialReception) {
        var allItems = inbox.findItems(event.id());
        if (allItems.size() > 1000) {
            throw LifecycleFailures.unavailable();
        }
        var items = allItems.stream().filter(item -> employeeId == null || employeeId.equals(item.employeeId())).toList();
        if (items.isEmpty() || items.size() > 1000) {
            throw LifecycleFailures.unavailable();
        }
        var byEmployee = new TreeMap<String, LifecycleAssignment>();
        var byUser = new TreeMap<Long, LifecycleAssignment>();
        for (var item : items) {
            if (item.status() == LifecycleItemStatus.UNMAPPED || item.status() == LifecycleItemStatus.FAILED) {
                var mapping = mappings.findEmployee("dingtalk", event.enterpriseId(), item.employeeId());
                if (mapping != null) {
                    byEmployee.put(item.employeeId(), mapping);
                    byUser.put(mapping.userId(), mapping);
                }
            }
        }
        for (long user : byUser.keySet()) {
            mappings.lockAssignment(user);
        }
        for (long user : byUser.keySet()) {
            if (users.findIdentityForUpdate(user) == null) {
                throw LifecycleSupport.stale();
            }
        }
        int restricted = 0;
        int review = 0;
        int unmapped = 0;
        for (var item : items) {
            if (item.status() == LifecycleItemStatus.REVIEW) {
                review++;
                continue;
            }
            if (!item.status().unresolved()) {
                continue;
            }
            var mapping = byEmployee.get(item.employeeId());
            if (mapping == null) {
                requireWritten(inbox.updateItem(item.id(), null, null, null, LifecycleItemStatus.UNMAPPED, "UNMATCHED"));
                unmapped++;
                continue;
            }
            boolean late = mapping.restoredAt() != null && !event.bornAt().isAfter(mapping.restoredAt());
            var restriction = late ? LifecycleAssignment.Restriction.REVIEW : LifecycleAssignment.Restriction.OFFBOARDED;
            String request = LifecycleSupport.digest(Map.of("event", event.id(), "user", mapping.userId(), "version", mapping.version()));
            var after = states.restrict(mapping.userId(), mapping.version(), event.id(), restriction, request, initialReception);
            boolean isolated = after.restriction() == LifecycleAssignment.Restriction.REVIEW;
            String error = isolated ? (late ? "LATE_EVENT" : "EMERGENCY_UNAVAILABLE") : null;
            requireWritten(inbox.updateItem(item.id(), mapping.userId(), mapping.version() + 1, after.authEpoch(),
                    isolated ? LifecycleItemStatus.REVIEW : LifecycleItemStatus.OFFBOARDED, error));
            if (isolated) {
                review++;
            } else {
                restricted++;
            }
        }
        return new Result(event.id(), false, false, restricted, review, unmapped);
    }

    private static void requireWritten(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("逐员工处置更新失败");
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
