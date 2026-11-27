package com.zhishiku.kb.app.service.subscription;

import com.zhishiku.kb.common.review.PublicationOrder;

import java.util.UUID;

/** 发布生产者使用的短事务协议；调用者必须先持有原资源锁。 */
public interface KnowledgePublicationService {
    PublicationOrder next();

    void capture(UUID eventId, PublicationOrder order);
}
