package com.zhishiku.kb.app.notification.service;

import com.zhishiku.kb.app.notification.NotificationProperties;
import com.zhishiku.kb.app.notification.mapper.GapNotificationDerivationMapper;
import com.zhishiku.kb.app.notification.model.ClaimedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 独立问题来源的短事务适配器；复用批次/回滚算法，不共享协作 projection 或读取正文。 */
@Service
public class GapNotificationDerivationTransaction extends NotificationDerivationTransaction {
    public static final Set<String> SOURCE_KINDS = Set.of("GAP_CREATED", "GAP_RETURNED", "GAP_ASSIGNED", "GAP_SOLUTION_SUBMITTED",
            "GAP_SOLUTION_REJECTED", "GAP_RESOLVED", "GAP_WITHDRAWN", "GAP_CLOSED");

    public GapNotificationDerivationTransaction(GapNotificationDerivationMapper mapper, NotificationProperties properties) {
        super(mapper, properties);
    }

    @Transactional(timeout = NotificationProperties.BATCH_TIMEOUT_SECONDS)
    public Batch batchGap(ClaimedEvent claim, String owner, long cursor) {
        if (!SOURCE_KINDS.contains(claim.kind())) {
            throw new IllegalArgumentException("Unsupported gap notification source");
        }
        return batchWithType(claim, owner, claim.kind(), cursor);
    }
}
