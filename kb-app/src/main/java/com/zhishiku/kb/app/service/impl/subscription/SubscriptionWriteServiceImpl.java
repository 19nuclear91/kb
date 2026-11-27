package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.domain.subscription.KnowledgeSubscription;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPeriod;
import com.zhishiku.kb.app.domain.subscription.SubscriptionPreference;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.enums.subscription.SubscriptionKind;
import com.zhishiku.kb.app.enums.subscription.SubscriptionMode;
import com.zhishiku.kb.app.enums.subscription.SubscriptionState;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.app.mapper.subscription.SubscriptionMapper;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.app.service.subscription.SubscriptionWriteService;
import com.zhishiku.kb.common.review.PublicationOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 原请求互斥、资源锁、生效顺序和业务记录依次取得；只更新本人关系。 */
@Service
@RequiredArgsConstructor
@Transactional(timeout = 10)
public class SubscriptionWriteServiceImpl implements SubscriptionWriteService {
    private final SubscriptionMapper mapper;
    private final KnowledgeOrderMapper orders;
    private final SubscriptionAccess access;
    private final SubscriptionCommands commands;

    @Override
    public SubscriptionViews.Receipt subscribe(long actor, String requestId, SubscriptionKind kind, long targetId, long expectedVersion) {
        if (kind == null || targetId <= 0 || expectedVersion < 0) {
            throw SubscriptionFailure.invalid();
        }
        access.current(actor);
        var binding = commands.bind(actor, requestId, "SUBSCRIBE", kind, targetId, expectedVersion);
        var previous = commands.begin(binding);
        if (previous != null) {
            return previous;
        }
        access.requireTarget(actor, kind, targetId);
        access.lockTarget(kind, targetId);
        var order = order();
        access.requireTarget(actor, kind, targetId);
        var old = mapper.findTarget(actor, kind, targetId);
        if ((old == null ? 0 : old.stateVersion()) != expectedVersion) {
            throw SubscriptionFailure.conflict("VERSION_CONFLICT");
        }
        if (old != null && old.state() == SubscriptionState.ACTIVE) {
            throw SubscriptionFailure.conflict("ALREADY_SUBSCRIBED");
        }
        if (mapper.activeCount(actor) >= 200) {
            throw SubscriptionFailure.conflict("SUBSCRIPTION_LIMIT");
        }
        UUID id = old == null ? UUID.randomUUID() : old.id();
        UUID generation = UUID.randomUUID();
        long version = Math.incrementExact(expectedVersion);
        var relation = new KnowledgeSubscription(id, actor, kind, targetId, SubscriptionState.ACTIVE, generation,
                version, old == null ? order.businessTime() : old.createdAt(), order.businessTime());
        SubscriptionRules.changed(old == null ? mapper.insert(relation) : mapper.advance(relation));
        SubscriptionRules.changed(mapper.insertPeriod(new SubscriptionPeriod(generation, id, actor, order.position(),
                order.businessTime(), null, null)));
        return commands.complete(binding, new SubscriptionViews.Receipt(binding.requestId(), "SUBSCRIBE", id, generation,
                Long.toString(version), SubscriptionState.ACTIVE, null, order.businessTime(), false));
    }

    @Override
    public SubscriptionViews.Receipt cancel(long actor, String requestId, UUID subscriptionId, UUID generationId, long expectedVersion) {
        if (subscriptionId == null || generationId == null || expectedVersion < 1) {
            throw SubscriptionFailure.invalid();
        }
        access.current(actor);
        var binding = commands.bind(actor, requestId, "CANCEL", subscriptionId, generationId, expectedVersion);
        var previous = commands.begin(binding);
        if (previous != null) {
            return previous;
        }
        var order = order();
        access.current(actor);
        var old = mapper.find(actor, subscriptionId);
        if (old == null) {
            throw SubscriptionFailure.hidden();
        }
        if (old.stateVersion() != expectedVersion || !old.generationId().equals(generationId)) {
            throw SubscriptionFailure.conflict("VERSION_CONFLICT");
        }
        if (old.state() != SubscriptionState.ACTIVE) {
            throw SubscriptionFailure.conflict("NO_CHANGE");
        }
        long version = Math.incrementExact(expectedVersion);
        SubscriptionRules.changed(mapper.closePeriod(actor, generationId, order.position(), order.businessTime()));
        SubscriptionRules.changed(mapper.advance(new KnowledgeSubscription(old.id(), actor, old.kind(), old.targetId(),
                SubscriptionState.CANCELLED, generationId, version, old.createdAt(), order.businessTime())));
        return commands.complete(binding, new SubscriptionViews.Receipt(binding.requestId(), "CANCEL", subscriptionId,
                generationId, Long.toString(version), SubscriptionState.CANCELLED, null, order.businessTime(), false));
    }

    @Override
    public SubscriptionViews.Receipt preference(long actor, String requestId, SubscriptionMode mode, long expectedVersion) {
        if (mode == null || expectedVersion < 0) {
            throw SubscriptionFailure.invalid();
        }
        access.current(actor);
        var binding = commands.bind(actor, requestId, "PREFERENCE", mode, expectedVersion);
        var previous = commands.begin(binding);
        if (previous != null) {
            return previous;
        }
        var order = order();
        access.current(actor);
        var old = mapper.preference(actor);
        if ((old == null ? 0 : old.version()) != expectedVersion) {
            throw SubscriptionFailure.conflict("VERSION_CONFLICT");
        }
        if ((old == null ? SubscriptionMode.WEEKLY : old.mode()) == mode) {
            throw SubscriptionFailure.conflict("NO_CHANGE");
        }
        long version = Math.incrementExact(expectedVersion);
        var preference = new SubscriptionPreference(actor, mode, version, order.position(), order.businessTime());
        SubscriptionRules.changed(mapper.savePreference(preference));
        SubscriptionRules.changed(mapper.appendPreference(preference));
        return commands.complete(binding, new SubscriptionViews.Receipt(binding.requestId(), "PREFERENCE", null, null,
                Long.toString(version), null, mode, order.businessTime(), false));
    }

    private PublicationOrder order() {
        PublicationOrder order = orders.next();
        if (order == null) {
            throw SubscriptionFailure.unavailable();
        }
        return order;
    }
}
