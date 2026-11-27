package com.zhishiku.kb.app.service.impl.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.domain.subscription.SubscriptionCommand;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPeriod;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPreference;
import com.zhishiku.kb.app.config.KnowledgeSubscriptionProperties;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.domain.subscription.KnowledgeDelivery;
import com.zhishiku.kb.app.enums.subscription.DeliveryState;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeDerivationMapper;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeQueryMapper;
import com.zhishiku.kb.common.security.PermissionScope;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionCommandMapper;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.review.PublicationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真服务与内存存储边界组合，不连接数据库，不宣称真实事务回滚已验证。 */
class SubscriptionWorkflowTest {
    private final SubscriptionMapper mapper = mock(SubscriptionMapper.class);
    private final KnowledgeOrderMapper orders = mock(KnowledgeOrderMapper.class);
    private final SubscriptionAccess access = mock(SubscriptionAccess.class);
    private final SubscriptionCommandMapper commandMapper = mock(SubscriptionCommandMapper.class);
    private final Map<String, SubscriptionCommand> receipts = new HashMap<>();
    private final Map<UUID, KnowledgeSubscription> relations = new HashMap<>();
    private final Map<UUID, SubscriptionPeriod> periods = new HashMap<>();
    private final Map<Long, SubscriptionPreference> preferences = new HashMap<>();
    private final AtomicLong position = new AtomicLong();
    private final SubscriptionCommands commands = new SubscriptionCommands(commandMapper, new ObjectMapper().findAndRegisterModules());
    private final SubscriptionWriteServiceImpl service = new SubscriptionWriteServiceImpl(mapper, orders, access, commands);
    private final Instant start = Instant.parse("2026-11-27T08:00:00Z");

    @BeforeEach
    void setup() {
        when(orders.next()).thenAnswer(call -> new PublicationOrder(position.incrementAndGet(), start.plusSeconds(position.get())));
        when(commandMapper.find(anyLong(), anyString())).thenAnswer(call -> receipts.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(commandMapper.insert(any())).thenAnswer(call -> {
            SubscriptionCommand command = call.getArgument(0);
            receipts.put(command.actorId() + ":" + command.requestId(), command);
            return 1;
        });
        when(commandMapper.complete(any())).thenAnswer(call -> {
            SubscriptionCommand command = call.getArgument(0);
            receipts.put(command.actorId() + ":" + command.requestId(), command);
            return 1;
        });
        when(mapper.findTarget(anyLong(), any(), anyLong())).thenAnswer(call -> relations.values().stream()
                .filter(row -> row.userId() == (long) call.getArgument(0) && row.kind() == call.getArgument(1)
                        && row.targetId() == (long) call.getArgument(2)).findFirst().orElse(null));
        when(mapper.find(anyLong(), any())).thenAnswer(call -> {
            var row = relations.get(call.getArgument(1));
            return row != null && row.userId() == (long) call.getArgument(0) ? row : null;
        });
        when(mapper.activeCount(anyLong())).thenAnswer(call -> relations.values().stream()
                .filter(row -> row.userId() == (long) call.getArgument(0) && row.state() == SubscriptionState.ACTIVE).count());
        when(mapper.insert(any())).thenAnswer(call -> save(call.getArgument(0)));
        when(mapper.advance(any())).thenAnswer(call -> save(call.getArgument(0)));
        when(mapper.insertPeriod(any())).thenAnswer(call -> {
            SubscriptionPeriod period = call.getArgument(0);
            periods.put(period.id(), period);
            return 1;
        });
        when(mapper.closePeriod(anyLong(), any(), anyLong(), any())).thenAnswer(call -> {
            var old = periods.get(call.getArgument(1));
            if (old == null || old.endPosition() != null || old.userId() != (long) call.getArgument(0)) {
                return 0;
            }
            periods.put(old.id(), new SubscriptionPeriod(old.id(), old.subscriptionId(), old.userId(), old.startPosition(),
                    old.startAt(), call.getArgument(2), call.getArgument(3)));
            return 1;
        });
        when(mapper.preference(anyLong())).thenAnswer(call -> preferences.get(call.getArgument(0)));
        when(mapper.savePreference(any())).thenAnswer(call -> {
            SubscriptionPreference preference = call.getArgument(0);
            preferences.put(preference.userId(), preference);
            return 1;
        });
        when(mapper.appendPreference(any())).thenReturn(1);
    }

    private int save(KnowledgeSubscription row) {
        relations.put(row.id(), row);
        return 1;
    }

    @Test
    void resubscriptionNeverLetsAnOldCancellationCloseTheNewGeneration() {
        var first = service.subscribe(1, "sub", SubscriptionKind.DOCUMENT, 10, 0);
        var cancelled = service.cancel(1, "cancel", first.subscriptionId(), first.generationId(), 1);
        var second = service.subscribe(1, "again", SubscriptionKind.DOCUMENT, 10, 2);
        var replay = service.cancel(1, "cancel", first.subscriptionId(), first.generationId(), 1);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.effectiveAt()).isEqualTo(cancelled.effectiveAt());
        assertThat(second.subscriptionId()).isEqualTo(first.subscriptionId());
        assertThat(second.generationId()).isNotEqualTo(first.generationId());
        assertThat(relations.get(first.subscriptionId()).state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(periods.get(second.generationId()).endPosition()).isNull();
        assertThat(periods.get(first.generationId()).endPosition()).isEqualTo(2);
        assertThat(position.get()).isEqualTo(3);
    }

    @Test
    void lostResponseReplaysMinimalReceiptAndRejectsDifferentIntent() {
        var original = service.subscribe(1, "same", SubscriptionKind.TAG, 10, 0);
        assertThat(service.subscribe(1, "same", SubscriptionKind.TAG, 10, 0).replayed()).isTrue();
        assertThat(commands.lookup(1, "same").receipt().subscriptionId()).isEqualTo(original.subscriptionId());
        assertThat(commands.lookup(2, "same").status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(() -> service.subscribe(1, "same", SubscriptionKind.TAG, 11, 0))
                .isInstanceOf(SubscriptionFailure.class).hasMessage("REQUEST_MISMATCH");
        assertThat(relations).hasSize(1);
        assertThat(periods).hasSize(1);
    }

    @Test
    void uniqueConflictAndMissingReceiptRemainUnconfirmedWithoutSuccessProjection() {
        doThrow(new org.springframework.dao.DuplicateKeyException("simulated constraint")).when(commandMapper).insert(any());
        assertThatThrownBy(() -> service.subscribe(1, "collision", SubscriptionKind.TAG, 10, 0))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(commands.lookup(1, "collision").status()).isEqualTo("UNKNOWN");
        assertThat(relations).isEmpty();
        assertThat(periods).isEmpty();
    }

    @Test
    void preferenceChangesAdvanceOnlyTheirOwnRevision() {
        var first = service.preference(1, "frequency-1", SubscriptionMode.PER_PUBLICATION, 0);
        var second = service.preference(1, "frequency-2", SubscriptionMode.WEEKLY, 1);
        assertThat(first.mode()).isEqualTo(SubscriptionMode.PER_PUBLICATION);
        assertThat(second.mode()).isEqualTo(SubscriptionMode.WEEKLY);
        assertThat(service.preference(1, "frequency-1", SubscriptionMode.PER_PUBLICATION, 0).mode())
                .isEqualTo(SubscriptionMode.PER_PUBLICATION);
        assertThat(preferences.get(1L).mode()).isEqualTo(SubscriptionMode.WEEKLY);
        assertThat(preferences.get(1L).version()).isEqualTo(2);
        assertThat(position.get()).isEqualTo(2);
    }

    @Test
    void onlyOwnerCanCancelButTargetReadAccessIsNotRequired() {
        var first = service.subscribe(1, "sub", SubscriptionKind.SPACE, 10, 0);
        doThrow(SubscriptionFailure.hidden()).when(access).requireTarget(1, SubscriptionKind.SPACE, 10);
        assertThat(service.cancel(1, "cancel", first.subscriptionId(), first.generationId(), 1).state())
                .isEqualTo(SubscriptionState.CANCELLED);
        assertThatThrownBy(() -> service.cancel(2, "foreign", first.subscriptionId(), first.generationId(), 1))
                .isInstanceOf(SubscriptionFailure.class).hasMessage("NOT_FOUND");
    }

    @Test
    void staleStateAndCapacityCannotReplaceAnExistingSubscription() {
        var first = service.subscribe(1, "sub", SubscriptionKind.DOCUMENT, 10, 0);
        assertThatThrownBy(() -> service.cancel(1, "stale", first.subscriptionId(), UUID.randomUUID(), 1))
                .isInstanceOf(SubscriptionFailure.class).hasMessage("VERSION_CONFLICT");
        when(mapper.activeCount(1)).thenReturn(200L);
        assertThatThrownBy(() -> service.subscribe(1, "full", SubscriptionKind.DOCUMENT, 11, 0))
                .isInstanceOf(SubscriptionFailure.class).hasMessage("SUBSCRIPTION_LIMIT");
        assertThat(relations.get(first.subscriptionId()).state()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void delayedPublicationPipelineKeepsOriginalGenerationsFrequencyAndWeeklyMembership() {
        var work = mock(KnowledgeDerivationMapper.class);
        var queries = mock(KnowledgeQueryMapper.class);
        var derivation = new KnowledgeDerivationTransaction(work, orders, queries, access, new KnowledgeSubscriptionProperties());
        var publication = new KnowledgePublicationServiceImpl(orders);
        var revisions = new ArrayList<SubscriptionPreference>();
        var sources = new HashMap<UUID, PublicationOrder>();
        var updates = new HashMap<UUID, KnowledgeWork.Update>();
        var supports = new HashMap<UUID, Set<UUID>>();
        var deliveries = new HashMap<UUID, KnowledgeDelivery>();
        var inbox = new HashSet<UUID>();
        when(access.current(1)).thenReturn(PermissionScope.of(1, (short) 1, List.of(20L), List.of("1:1")));
        when(mapper.appendPreference(any())).thenAnswer(call -> {
            revisions.add(call.getArgument(0));
            return 1;
        });
        when(orders.capture(any(), anyLong(), any())).thenAnswer(call -> {
            sources.put(call.getArgument(0), new PublicationOrder(call.getArgument(1), call.getArgument(2)));
            return 1;
        });
        when(work.lockMatch(any(), anyString())).thenReturn(0L);
        when(work.sourceReady(any())).thenReturn(true);
        when(work.recipients(any(), anyLong(), anyInt())).thenReturn(List.of(1L));
        // 以不可变发布归属及历史区间实现仓储合同，不访问当前目标归属或标签启用状态。
        when(work.matches(any(), any())).thenAnswer(call -> {
            UUID eventId = call.getArgument(0);
            var source = sources.get(eventId);
            var mode = revisions.stream().filter(revision -> revision.effectivePosition() < source.position())
                    .max(Comparator.comparingLong(SubscriptionPreference::effectivePosition))
                    .map(SubscriptionPreference::mode).orElse(SubscriptionMode.WEEKLY);
            return periods.values().stream().filter(period -> period.startPosition() < source.position()
                            && (period.endPosition() == null || period.endPosition() > source.position()))
                    .filter(period -> {
                        var relation = relations.get(period.subscriptionId());
                        return switch (relation.kind()) {
                            case DOCUMENT -> relation.targetId() == 10;
                            case SPACE -> relation.targetId() == 20;
                            case TAG -> Set.of(30L, 31L).contains(relation.targetId());
                        };
                    }).map(period -> new KnowledgeWork.Match(period.userId(), period.id(), mode, KnowledgeCalendar.week(source.businessTime()))).toList();
        });
        when(work.saveUpdate(any())).thenAnswer(call -> {
            KnowledgeWork.Update value = call.getArgument(0);
            var previous = updates.values().stream().filter(row -> row.userId() == value.userId() && row.eventId().equals(value.eventId())).findFirst();
            if (previous.isPresent()) {
                return previous.get().id();
            }
            updates.put(value.id(), value);
            supports.put(value.id(), new HashSet<>());
            return value.id();
        });
        when(work.saveMatch(any(), anyLong(), any())).thenAnswer(call -> supports.get(call.getArgument(0)).add(call.getArgument(2)) ? 1 : 0);
        when(work.registerImmediate(any(), anyLong())).thenAnswer(call -> {
            UUID id = call.getArgument(0);
            deliveries.putIfAbsent(id, new KnowledgeDelivery(id, call.getArgument(1), SubscriptionMode.PER_PUBLICATION, id, null, DeliveryState.PENDING, null, null));
            return 1;
        });
        when(work.advanceMatch(any(), anyString(), anyLong(), anyBoolean(), anyInt())).thenReturn(1);
        when(work.lockDelivery(any(), anyString())).thenAnswer(call -> {
            KnowledgeWork.Claim current = call.getArgument(0);
            var row = deliveries.get(current.id());
            return row != null && row.status() == DeliveryState.PENDING ? row : null;
        });
        when(queries.eligible(any())).thenAnswer(call -> {
            com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery query = call.getArgument(0);
            return updates.values().stream().filter(row -> row.userId() == query.actor()
                            && (query.updateId() != null ? row.id().equals(query.updateId())
                            : row.mode() == SubscriptionMode.WEEKLY && row.weekStart().equals(query.weekStart())))
                    .anyMatch(row -> supports.get(row.id()).stream().map(periods::get)
                            .anyMatch(period -> period.endPosition() == null || period.endPosition() > query.dispatchPosition()));
        });
        when(work.finishDelivery(any(), anyString(), anyBoolean(), anyLong(), any())).thenAnswer(call -> {
            KnowledgeWork.Claim current = call.getArgument(0);
            var old = deliveries.get(current.id());
            deliveries.put(old.id(), new KnowledgeDelivery(old.id(), old.userId(), old.kind(), old.updateId(), old.weekStart(),
                    (boolean) call.getArgument(2) ? DeliveryState.NOTIFIED : DeliveryState.NO_ELIGIBLE, call.getArgument(3), call.getArgument(4)));
            return 1;
        });
        when(work.notify(any())).thenAnswer(call -> inbox.add(((KnowledgeDelivery) call.getArgument(0)).id()) ? 1 : 0);

        var document = service.subscribe(1, "doc", SubscriptionKind.DOCUMENT, 10, 0);
        var space = service.subscribe(1, "space", SubscriptionKind.SPACE, 20, 0);
        var tag = service.subscribe(1, "tag", SubscriptionKind.TAG, 30, 0);
        var otherTag = service.subscribe(1, "tag2", SubscriptionKind.TAG, 31, 0);
        UUID first = UUID.randomUUID();
        publication.capture(first, publication.next());
        service.cancel(1, "cancel-doc", document.subscriptionId(), document.generationId(), 1);
        service.preference(1, "per-publication", SubscriptionMode.PER_PUBLICATION, 0);
        UUID second = UUID.randomUUID();
        publication.capture(second, publication.next());
        service.cancel(1, "cancel-tag", tag.subscriptionId(), tag.generationId(), 1);
        var newDocument = service.subscribe(1, "new-doc", SubscriptionKind.DOCUMENT, 10, 2);
        derivation.match(new KnowledgeWork.Claim(first, null, 1, 0, 0, false), "app");
        derivation.match(new KnowledgeWork.Claim(second, null, 1, 0, 0, false), "app");
        // 同批重试保持唯一更新和完整原匹配集合。
        derivation.match(new KnowledgeWork.Claim(first, null, 1, 0, 0, false), "app");
        assertThat(updates).hasSize(2);
        var weekly = updates.values().stream().filter(row -> row.mode() == SubscriptionMode.WEEKLY).findFirst().orElseThrow();
        assertThat(supports.get(weekly.id())).hasSize(4).contains(document.generationId()).doesNotContain(newDocument.generationId());
        var immediate = updates.values().stream().filter(row -> row.mode() == SubscriptionMode.PER_PUBLICATION).findFirst().orElseThrow();
        assertThat(supports.get(immediate.id())).hasSize(3).doesNotContain(newDocument.generationId());
        var immediateClaim = new KnowledgeWork.Claim(immediate.id(), null, 1, 0, 0, false);
        derivation.deliver(immediateClaim, "app");
        assertThat(inbox).containsExactly(immediate.id());

        // 周报登记仍沿原周；这里只模拟数据库已完成封口与匹配的返回事实。
        var weeklyClaim = new KnowledgeWork.Claim(null, weekly.weekStart(), 1, 0, 0, false);
        when(work.lockWeek(weeklyClaim, "app")).thenReturn(0L);
        when(work.weekComplete(weekly.weekStart())).thenReturn(true);
        when(work.weeklyRecipients(weekly.weekStart(), 0, 100)).thenReturn(List.of(1L));
        UUID digest = UUID.randomUUID();
        when(work.registerWeekly(weekly.weekStart(), 1)).thenAnswer(call -> {
            deliveries.put(digest, new KnowledgeDelivery(digest, 1, SubscriptionMode.WEEKLY, null, weekly.weekStart(), DeliveryState.PENDING, null, null));
            return 1;
        });
        when(work.advanceWeek(any(), anyString(), anyLong(), anyBoolean(), anyInt())).thenReturn(1);
        derivation.week(weeklyClaim, "app");
        service.cancel(1, "cancel-space", space.subscriptionId(), space.generationId(), 1);
        service.cancel(1, "cancel-tag2", otherTag.subscriptionId(), otherTag.generationId(), 1);
        derivation.deliver(new KnowledgeWork.Claim(digest, weekly.weekStart(), 1, 0, 0, false), "app");
        assertThat(deliveries.get(digest).status()).isEqualTo(DeliveryState.NO_ELIGIBLE);
        assertThat(updates).hasSize(2);
        assertThat(inbox).containsExactly(immediate.id());
        assertThatThrownBy(() -> derivation.deliver(immediateClaim, "app")).isInstanceOf(KnowledgeLeaseLost.class);
        assertThat(deliveries.get(immediate.id()).status()).isEqualTo(DeliveryState.NOTIFIED);
    }
}
