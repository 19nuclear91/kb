package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.config.KnowledgeSubscriptionProperties;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeWork;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeDerivationMapper;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeQueryMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.IdentityException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** 每批独立短事务，调用方必须经Spring代理，失败由外层循环退避。 */
@Service
@RequiredArgsConstructor
@Transactional(timeout = 10)
public class KnowledgeDerivationTransaction {
    private final KnowledgeDerivationMapper mapper;
    private final KnowledgeOrderMapper orders;
    private final KnowledgeQueryMapper queries;
    private final SubscriptionAccess access;
    private final KnowledgeSubscriptionProperties properties;

    public void discover() {
        mapper.discover(properties.getDiscoverLimit());
    }

    public List<KnowledgeWork.Claim> claim(String stage, String owner) {
        return switch (stage) {
            case "match" -> mapper.claimMatches(owner, properties.getClaimLimit(), properties.getLeaseSeconds());
            case "weekly" -> mapper.claimWeeks(owner, properties.getClaimLimit(), properties.getLeaseSeconds());
            case "delivery" -> mapper.claimDeliveries(owner, properties.getClaimLimit(), properties.getLeaseSeconds());
            default -> throw new IllegalArgumentException("Invalid knowledge stage");
        };
    }

    public boolean match(KnowledgeWork.Claim claim, String owner) {
        Long cursor = mapper.lockMatch(claim, owner);
        if (cursor == null) {
            throw new KnowledgeLeaseLost();
        }
        if (!mapper.sourceReady(claim.id())) {
            requireProgress(mapper.waitSource(claim, owner, properties.backoff(claim.attempts())));
            return true;
        }
        var users = mapper.recipients(claim.id(), cursor, properties.getRecipientBatchSize());
        if (users == null || users.size() > properties.getRecipientBatchSize()) {
            throw SubscriptionFailure.unavailable();
        }
        if (!users.isEmpty()) {
            var matches = mapper.matches(claim.id(), users);
            if (matches == null || matches.size() > users.size() * 22) {
                throw SubscriptionFailure.unavailable();
            }
            var groups = new LinkedHashMap<Long, List<KnowledgeWork.Match>>();
            for (long user : users) {
                var group = matches.stream().filter(value -> value.userId() == user).toList();
                if (group.isEmpty() || group.size() > 22 || user <= cursor) {
                    throw SubscriptionFailure.unavailable();
                }
                groups.put(user, group);
            }
            for (var entry : groups.entrySet()) {
                var first = entry.getValue().getFirst();
                UUID update = mapper.saveUpdate(new KnowledgeWork.Update(UUID.randomUUID(), first.userId(), claim.id(), first.mode(), first.weekStart()));
                if (update == null) {
                    throw SubscriptionFailure.unavailable();
                }
                for (var match : entry.getValue()) {
                    if (match.mode() != first.mode() || !match.weekStart().equals(first.weekStart())) {
                        throw SubscriptionFailure.unavailable();
                    }
                    mapper.saveMatch(update, first.userId(), match.generationId());
                }
                if (first.mode() == SubscriptionMode.PER_PUBLICATION) {
                    mapper.registerImmediate(update, first.userId());
                }
            }
        }
        boolean done = users.size() < properties.getRecipientBatchSize();
        long next = users.isEmpty() ? cursor : users.getLast();
        requireProgress(mapper.advanceMatch(claim, owner, next, done, properties.getLeaseSeconds()));
        return done;
    }

    public boolean discoverWeek() {
        var order = orders.next();
        if (order == null) {
            throw SubscriptionFailure.unavailable();
        }
        var week = mapper.nextWeek();
        if (week == null || order.businessTime().isBefore(KnowledgeCalendar.due(week))) {
            return false;
        }
        SubscriptionRules.changed(mapper.createWeek(week, order.position(), order.businessTime()));
        return true;
    }

    public boolean week(KnowledgeWork.Claim claim, String owner) {
        Long cursor = mapper.lockWeek(claim, owner);
        if (cursor == null) {
            throw new KnowledgeLeaseLost();
        }
        if (!mapper.weekComplete(claim.weekStart())) {
            release("weekly", claim, owner, properties.getBackoffInitialSeconds(), false);
            return true;
        }
        var users = mapper.weeklyRecipients(claim.weekStart(), cursor, properties.getRecipientBatchSize());
        if (users == null || users.size() > properties.getRecipientBatchSize()) {
            throw SubscriptionFailure.unavailable();
        }
        for (long user : users) {
            if (user <= cursor) {
                throw SubscriptionFailure.unavailable();
            }
            mapper.registerWeekly(claim.weekStart(), user);
        }
        boolean done = users.size() < properties.getRecipientBatchSize();
        requireProgress(mapper.advanceWeek(claim, owner, users.isEmpty() ? cursor : users.getLast(), done, properties.getLeaseSeconds()));
        return done;
    }

    public boolean deliver(KnowledgeWork.Claim claim, String owner) {
        var order = orders.next();
        if (order == null) {
            throw SubscriptionFailure.unavailable();
        }
        var delivery = mapper.lockDelivery(claim, owner);
        if (delivery == null) {
            throw new KnowledgeLeaseLost();
        }
        boolean eligible;
        try {
            var scope = access.current(delivery.userId());
            eligible = queries.eligible(new KnowledgeQuery(delivery.userId(), scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                    scope.clearanceLevel(), delivery.updateId(), null, null, delivery.weekStart(), order.position(),
                    null, null, null, 1));
        } catch (IdentityException failure) {
            if (failure.httpStatus() != 401 && failure.httpStatus() != 403) {
                throw failure;
            }
            eligible = false;
        }
        requireProgress(mapper.finishDelivery(claim, owner, eligible, order.position(), order.businessTime()));
        if (eligible) {
            SubscriptionRules.changed(mapper.notify(delivery));
        }
        return true;
    }

    public void release(String stage, KnowledgeWork.Claim claim, String owner, int delay, boolean failed) {
        requireProgress(mapper.release(stage, claim, owner, delay, failed));
    }

    private void requireProgress(int affected) {
        if (affected != 1) {
            throw new KnowledgeLeaseLost();
        }
    }
}
