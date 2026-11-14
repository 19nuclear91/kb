package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;

/** 源事务固定收件人意图；不依赖通知模块，不复制共享正文或提供通用发送入口。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class KnowledgeGapEventService {
    private final KnowledgeGapMapper mapper;

    public KnowledgeGapEventService(KnowledgeGapMapper mapper) {
        this.mapper = mapper;
    }

    public void record(KnowledgeGapRows.Event event, long reporterUserId) {
        KnowledgeGapFailures.guarded(() -> {
            if (event == null || event.eventId() == null || event.gapId() <= 0 || event.actorUserId() <= 0
                    || reporterUserId <= 0 || event.resultVersion() < 0) {
                throw KnowledgeGapFailures.unavailable();
            }
            if (mapper.insertEvent(event) != 1) {
                throw KnowledgeGapFailures.unavailable();
            }
            int inserted;
            switch (event.kind()) {
                case "GAP_CREATED", "GAP_RETURNED" -> inserted = mapper.insertReceptionRecipients(event.eventId());
                case "NOTE_ADDED" -> {
                    return null;
                }
                default -> {
                    var recipients = new LinkedHashSet<Long>();
                    switch (event.kind()) {
                        case "GAP_ASSIGNED", "GAP_SOLUTION_REJECTED", "GAP_RESOLVED", "GAP_WITHDRAWN" -> {
                            if (event.assigneeUserId() != null) {
                                recipients.add(event.assigneeUserId());
                            }
                        }
                        case "GAP_SOLUTION_SUBMITTED" -> recipients.add(reporterUserId);
                        case "GAP_CLOSED" -> {
                            recipients.add(reporterUserId);
                            if (event.assigneeUserId() != null) {
                                recipients.add(event.assigneeUserId());
                            }
                        }
                        default -> throw KnowledgeGapFailures.unavailable();
                    }
                    recipients.remove(event.actorUserId());
                    inserted = recipients.isEmpty() ? 0 : mapper.insertRecipients(event.eventId(), recipients.stream().toList());
                }
            }
            if (inserted < 0) {
                throw KnowledgeGapFailures.unavailable();
            }
            return null;
        });
    }
}
