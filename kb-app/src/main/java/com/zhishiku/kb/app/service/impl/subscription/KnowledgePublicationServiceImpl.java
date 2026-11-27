package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.mapper.subscription.KnowledgeOrderMapper;
import com.zhishiku.kb.app.service.subscription.KnowledgePublicationService;
import com.zhishiku.kb.common.review.PublicationOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** 发布与订阅上下文共用调用方事务，失败传播给原发布操作。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class KnowledgePublicationServiceImpl implements KnowledgePublicationService {
    private final KnowledgeOrderMapper mapper;

    @Override
    public PublicationOrder next() {
        PublicationOrder order = mapper.next();
        if (order == null) {
            throw new IllegalStateException("Knowledge publication order is unavailable");
        }
        return order;
    }

    @Override
    public void capture(UUID eventId, PublicationOrder order) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(order, "order");
        if (mapper.capture(eventId, order.position(), order.businessTime()) != 1) {
            throw new IllegalStateException("Knowledge publication context was not saved");
        }
    }
}
