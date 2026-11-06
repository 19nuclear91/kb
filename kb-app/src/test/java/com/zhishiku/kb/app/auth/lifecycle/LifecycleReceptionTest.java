package com.zhishiku.kb.app.auth.lifecycle;

import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.model.EmergencyIdentityFact;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.UserRolePermission;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.ManagementInvariant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LifecycleReceptionTest {
    @Test
    void commitEvidenceIsObservedAfterCommitOnlyAndReplayNeverOverwritesItsFirstBoundary() {
        var evidence = mock(OutputStopEvidenceService.class);
        receiver.setEvidence(evidence);
        doAnswer(call -> {
            assertThat(committed).isEqualTo(1);
            assertThat(user.getEnabled()).isFalse();
            return null;
        }).when(evidence).committed(anyLong(), eq(true));
        var incoming = event("business", "first", "user_leave_org", List.of("staff"));
        receiver.receive(incoming);
        receiver.receive(incoming);
        org.mockito.Mockito.verify(evidence, org.mockito.Mockito.times(1)).committed(anyLong(), eq(true));
    }

    @Test
    void rolledBackReceptionNeverPublishesACommitBoundary() {
        var evidence = mock(OutputStopEvidenceService.class);
        receiver.setEvidence(evidence);
        failCommit = true;
        assertThatThrownBy(() -> receiver.receive(event("business", "first", "user_leave_org", List.of("staff"))))
                .isInstanceOf(TransactionSystemException.class);
        org.mockito.Mockito.verifyNoInteractions(evidence);
    }
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper mappings = mock(LifecycleMapper.class);
    private final LifecycleInboxMapper inbox = mock(LifecycleInboxMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final MutableClock clock = new MutableClock();
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final LifecycleObservations observations = new LifecycleObservations(registry);
    private Instant commitAt;
    private Map<Long, LifecycleEventRecord> events = new HashMap<>();
    private Map<Long, LifecycleItem> items = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private IdentityRecord user = user(1, 0, LifecycleAssignment.Restriction.CLEAR);
    private LifecycleAssignment mapping = mapping(0, LifecycleAssignment.Restriction.CLEAR, null, null);
    private boolean mapped = true;
    private int audits;
    private int cleanup;
    private int committed;
    private boolean failCommit;
    private final MemoryTransactions manager = new MemoryTransactions();
    private final LifecycleReception receiver = new LifecycleReception(users, mappings, inbox,
            new LifecycleStateService(users, mappings, audit), manager, clock, observations);

    @BeforeEach
    void repository() {
        manager.setRollbackOnCommitFailure(true);
        when(inbox.nextId()).thenAnswer(call -> ids.incrementAndGet());
        when(inbox.findEvent(anyString(), anyString())).thenAnswer(call -> events.values().stream()
                .filter(event -> event.sourceScope().equals(call.getArgument(0)) && event.eventId().equals(call.getArgument(1)))
                .findFirst().orElse(null));
        when(inbox.findEventById(anyLong())).thenAnswer(call -> events.get(call.getArgument(0, Long.class)));
        when(inbox.insertEvent(any())).thenAnswer(call -> {
            var event = call.getArgument(0, LifecycleEventRecord.class);
            events.put(event.id(), event);
            return 1;
        });
        when(inbox.markEventConflict(anyLong())).thenAnswer(call -> {
            var old = events.get(call.getArgument(0, Long.class));
            events.put(old.id(), new LifecycleEventRecord(old.id(), old.sourceScope(), old.eventId(), old.eventType(),
                    old.enterpriseId(), old.connectionId(), old.bornAt(), old.contentDigest(), true, old.receivedAt()));
            return 1;
        });
        when(inbox.insertEmployee(anyLong(), anyString())).thenAnswer(call -> {
            long id = ids.incrementAndGet();
            items.put(id, new LifecycleItem(id, call.getArgument(0), call.getArgument(1), null, null, null,
                    LifecycleItemStatus.UNMAPPED, null, 0, null, clock.instant(), clock.instant(), null));
            return 1;
        });
        when(inbox.findItems(anyLong())).thenAnswer(call -> items.values().stream()
                .filter(item -> item.eventId() == call.getArgument(0, Long.class)).sorted(java.util.Comparator.comparingLong(LifecycleItem::id)).toList());
        when(inbox.updateItem(anyLong(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            var old = items.get(call.getArgument(0, Long.class));
            items.put(old.id(), new LifecycleItem(old.id(), old.eventId(), old.employeeId(), call.getArgument(1),
                    call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5), old.attempts() + 1,
                    null, old.createdAt(), clock.instant(), old.resolvedAt()));
            return 1;
        });
        when(mappings.findEmployee(eq("dingtalk"), eq("corp"), anyString())).thenAnswer(call ->
                mapped && Objects.equals(call.getArgument(2), "staff") ? mapping : null);
        when(mappings.lockAssignment(7)).thenAnswer(call -> mapping);
        when(users.findIdentityForUpdate(7)).thenAnswer(call -> user);
        when(mappings.updateRestriction(eq(7L), anyLong(), any(), any(), any())).thenAnswer(call -> {
            mapping = mapping(mapping.version() + 1, call.getArgument(2), call.getArgument(3), call.getArgument(4));
            return 1;
        });
        when(users.setUserAccessState(eq(7L), anyBoolean(), anyBoolean(), anyLong(), anyLong())).thenAnswer(call -> {
            assertThat(user.getVersion()).isEqualTo(call.getArgument(4, Long.class));
            user = user(call.getArgument(3), user.getVersion() + 1, mapping.restriction());
            return 1;
        });
        when(mappings.appendHistory(anyLong(), any(), anyString(), anyString())).thenReturn(1);
        when(users.enqueueSessionCleanup(anyLong(), anyLong())).thenAnswer(call -> ++cleanup);
        doAnswer(call -> {
            audits++;
            return null;
        }).when(audit).successWithReason(any(), anyString(), any(), any(), anyString(), anyString(), anyString(), anyString());
        when(users.findEmergencyIdentities()).thenReturn(List.of(
                new EmergencyIdentityFact(8, 1L, "EMERGENCY", true, true, true, true, false, "CLEAR")));
        var permissions = ManagementInvariant.EMERGENCY_PERMISSIONS.stream().map(code -> new UserRolePermission(8L, 1L, code)).toList();
        when(users.findEmergencyPermissions()).thenReturn(permissions);
        when(users.findEnabledManagementPermissions()).thenReturn(permissions);
    }

    @Test
    void mappedBarriersUnmappedItemsAuditAndCleanupCommitBeforeReturn() {
        receiver.receive(event("business", "first", "user_leave_org", List.of("staff", "unknown", "staff")));
        assertThat(committed).isEqualTo(1);
        assertThat(user.getEnabled()).isFalse();
        assertThat(user.getAuthEpoch()).isEqualTo(2);
        assertThat(items.values()).extracting(LifecycleItem::status).containsExactlyInAnyOrder(LifecycleItemStatus.OFFBOARDED, LifecycleItemStatus.UNMAPPED);
        assertThat(audits).isEqualTo(1);
        assertThat(cleanup).isEqualTo(1);
    }

    @Test
    void failedCommitRollsBackAllFactsAndDoesNotReturnSuccess() {
        failCommit = true;
        assertThatThrownBy(() -> receiver.receive(event("business", "first", "user_leave_org", List.of("staff"))))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(events).isEmpty();
        assertThat(items).isEmpty();
        assertThat(user.getEnabled()).isTrue();
        assertThat(user.getAuthEpoch()).isEqualTo(1);
        assertThat(audits).isZero();
        assertThat(cleanup).isZero();
        assertThat(committed).isZero();
    }

    @Test
    void restartReplayAndSameKeyConflictPreserveFirstEvidenceAndDoNotRevokeAgain() {
        var original = event("business", "first", "user_leave_org", List.of("staff"));
        receiver.receive(original);
        var restarted = new LifecycleReception(users, mappings, inbox, new LifecycleStateService(users, mappings, audit), manager, clock, observations);
        restarted.receive(original);
        restarted.receive(event("business", "different", "user_leave_org", List.of("unknown")));
        assertThat(events).hasSize(1);
        assertThat(events.values().iterator().next().contentDigest()).isEqualTo("first");
        assertThat(events.values().iterator().next().conflicted()).isTrue();
        assertThat(items).hasSize(1);
        assertThat(items.values().iterator().next().employeeId()).isEqualTo("staff");
        assertThat(user.getAuthEpoch()).isEqualTo(2);
        assertThat(audits).isEqualTo(1);
        assertThat(cleanup).isEqualTo(1);
    }

    @Test
    void currentMappingCanResolveOriginalUnmatchedItemButFinishedReplayCannotUndoRestoration() {
        mapped = false;
        receiver.receive(event("business", "first", "user_leave_org", List.of("staff")));
        long eventId = events.keySet().iterator().next();
        assertThat(user.getEnabled()).isTrue();
        mapped = true;
        new TransactionTemplate(manager).executeWithoutResult(status -> receiver.reprocessWithinTransaction(eventId));
        assertThat(user.getEnabled()).isFalse();
        mapping = mapping(2, LifecycleAssignment.Restriction.CLEAR, eventId, clock.instant());
        user = user(3, 2, LifecycleAssignment.Restriction.CLEAR);
        new TransactionTemplate(manager).executeWithoutResult(status -> receiver.reprocessWithinTransaction(eventId));
        assertThat(user.getEnabled()).isTrue();
        assertThat(user.getAuthEpoch()).isEqualTo(3);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void lateUnseenEventAndBrokenEmergencyDenyAccessAsReview() {
        mapping = mapping(0, LifecycleAssignment.Restriction.CLEAR, null, clock.instant());
        receiver.receive(event("late", "late", "user_leave_org", List.of("staff")));
        assertThat(mapping.restriction()).isEqualTo(LifecycleAssignment.Restriction.REVIEW);
        assertThat(user.getEnabled()).isFalse();
        assertThat(items.values().iterator().next().errorCode()).isEqualTo("LATE_EVENT");
        mapping = mapping(2, LifecycleAssignment.Restriction.CLEAR, null, null);
        user = user(3, 2, LifecycleAssignment.Restriction.CLEAR);
        when(users.findEmergencyIdentities()).thenReturn(List.of());
        receiver.receive(event("new", "new", "user_leave_org", List.of("staff")));
        assertThat(user.getEnabled()).isFalse();
        assertThat(mapping.restriction()).isEqualTo(LifecycleAssignment.Restriction.REVIEW);
        assertThat(items.values()).extracting(LifecycleItem::errorCode).contains("EMERGENCY_UNAVAILABLE");
    }

    @Test
    void knownEventAfterRestorationIsNoopButNewerDepartureRevokesAgain() {
        receiver.receive(event("original", "original", "user_leave_org", List.of("staff")));
        long originalId = events.keySet().iterator().next();
        mapping = mapping(2, LifecycleAssignment.Restriction.CLEAR, originalId, clock.instant());
        user = user(3, 2, LifecycleAssignment.Restriction.CLEAR);
        receiver.receive(event("original", "original", "user_leave_org", List.of("staff")));
        assertThat(user.getAuthEpoch()).isEqualTo(3);
        assertThat(user.getEnabled()).isTrue();
        clock.now = clock.now.plusSeconds(20);
        receiver.receive(event("newer", "newer", "user_leave_org", List.of("staff")));
        assertThat(user.getAuthEpoch()).isEqualTo(4);
        assertThat(user.getEnabled()).isFalse();
        assertThat(mapping.restriction()).isEqualTo(LifecycleAssignment.Restriction.OFFBOARDED);
    }

    @Test
    void joinEventNeverCreatesOrEnablesUsers() {
        receiver.receive(event("join", "join", "user_add_org", List.of()));
        assertThat(events).hasSize(1);
        assertThat(items).isEmpty();
        assertThat(audits).isZero();
        assertThat(user.getAuthEpoch()).isEqualTo(1);
    }

    @Test
    void receiptTimingStartsAfterCommitAndUnmappedOrFailedWorkDoesNotCreateAccessLatencySample() {
        mapped = false;
        Instant before = clock.instant();
        commitAt = before.plusSeconds(12);
        receiver.receive(event("unmapped", "one", "user_leave_org", List.of("staff")));
        assertThat(registry.get("kb.lifecycle.last_received_timestamp_seconds").gauge().value())
                .isEqualTo(commitAt.toEpochMilli() / 1000.0);
        assertThat(events.values().iterator().next().receivedAt()).isEqualTo(before);
        assertThat(registry.find("kb.lifecycle.access_barrier_delay").timer()).isNull();
        failCommit = true;
        assertThatThrownBy(() -> receiver.receive(event("failed", "two", "user_leave_org", List.of("staff"))))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(registry.get("kb.lifecycle.reception").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
        assertThat(registry.get("kb.lifecycle.reception").tag("outcome", "COMMITTED").counter().count()).isEqualTo(1);
        assertThat(registry.find("kb.lifecycle.access_barrier_delay").timer()).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-02-01T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private LifecycleIncomingEvent event(String id, String digest, String type, List<String> employees) {
        return new LifecycleIncomingEvent("scope", id, type, "corp", "connection", clock.instant().minusSeconds(10), digest, employees);
    }

    private static IdentityRecord user(long epoch, long version, LifecycleAssignment.Restriction restriction) {
        return new IdentityRecord(7L, "user", "员工", 1L, (short) 1, restriction == LifecycleAssignment.Restriction.CLEAR,
                version, null, null, true, epoch, restriction.name());
    }

    private static LifecycleAssignment mapping(long version, LifecycleAssignment.Restriction restriction, Long event, Instant restoredAt) {
        return new LifecycleAssignment(7, LifecycleAssignment.Kind.MANAGED, "dingtalk", "corp", "staff", "connection",
                restriction, version, event, restoredAt, 8L, null, null);
    }

    private final class MemoryTransactions extends AbstractPlatformTransactionManager {
        private Map<Long, LifecycleEventRecord> savedEvents;
        private Map<Long, LifecycleItem> savedItems;
        private IdentityRecord savedUser;
        private LifecycleAssignment savedMapping;
        private int savedAudits;
        private int savedCleanup;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertThat(definition.getTimeout()).isIn(-1, 5);
            savedEvents = new HashMap<>(events);
            savedItems = new HashMap<>(items);
            savedUser = user;
            savedMapping = mapping;
            savedAudits = audits;
            savedCleanup = cleanup;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) {
                throw new TransactionSystemException("commit failed");
            }
            committed++;
            if (commitAt != null) {
                clock.now = commitAt;
            }
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events = savedEvents;
            items = savedItems;
            user = savedUser;
            mapping = savedMapping;
            audits = savedAudits;
            cleanup = savedCleanup;
        }
    }
}
