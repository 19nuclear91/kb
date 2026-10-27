package com.zhishiku.kb.app.collaboration.service;

import com.zhishiku.kb.app.collaboration.mapper.CollaborationEventMapper;
import com.zhishiku.kb.common.review.CollaborationEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;

/** 只保存业务事实与去重提醒意图，不调用通知消费者。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class CollaborationEventService {
    private final CollaborationEventMapper mapper;

    public CollaborationEventService(CollaborationEventMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 与源业务同事务追加事实与提醒意图；事实已存在时不重复写收件人。
     *
     * @return 本次是否新写入事实
     */
    public boolean append(CollaborationEvent event, Collection<Long> recipients) {
        int coverage = event.kind().comment()
                ? mapper.startCommentCoverage(event.occurredAt())
                : mapper.startCoverage(event.occurredAt());
        if (coverage < 0 || coverage > 1) {
            throw new IllegalStateException("Collaboration coverage was not saved");
        }
        int rows = mapper.append(event);
        if (rows == 0) {
            return false;
        }
        if (rows != 1) {
            throw new IllegalStateException("Collaboration event was not saved");
        }
        recipients.stream().filter(Objects::nonNull).filter(id -> !Objects.equals(id, event.actorUserId()))
                .distinct().sorted().forEach(id -> {
                    if (id <= 0 || mapper.recipient(event.eventId(), id) != 1) {
                        throw new IllegalStateException("Collaboration recipient was not saved");
                    }
                });
        return true;
    }
}
