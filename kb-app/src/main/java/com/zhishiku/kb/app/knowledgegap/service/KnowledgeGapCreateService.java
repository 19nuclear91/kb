package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapState;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** 只接受主动分享的描述；命令、容量检查、问题、事实、意图和回执同事务提交。 */
@Service
public class KnowledgeGapCreateService {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapMapper mapper;
    private final KnowledgeGapCommandService commands;
    private final KnowledgeGapEventService events;

    public KnowledgeGapCreateService(KnowledgeGapAccess access, KnowledgeGapMapper mapper,
                                     KnowledgeGapCommandService commands, KnowledgeGapEventService events) {
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.events = events;
    }

    @Transactional(timeout = 5)
    public KnowledgeGapRows.Receipt create(KnowledgeGapInputs.Creation input, String requestId) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            var previous = commands.claim(actor, KnowledgeGapAction.CREATE, null, null, input, requestId);
            if (previous != null) {
                return previous;
            }
            mapper.ensureActorGuard(actor.userId());
            if (!Objects.equals(mapper.lockActorGuard(actor.userId()), actor.userId())) {
                throw KnowledgeGapFailures.unavailable();
            }
            long open = mapper.countOpen(actor.userId());
            if (open < 0) {
                throw KnowledgeGapFailures.unavailable();
            }
            if (open >= KnowledgeGapInputs.MAX_OPEN_GAPS) {
                throw ApiException.conflict("本人未结束的知识问题单已达上限");
            }
            UUID eventId = UUID.randomUUID();
            var gap = mapper.create(actor.userId(), input, KnowledgeGapInputs.CONSENT_VERSION, eventId);
            if (gap == null || gap.id() <= 0 || gap.reporterUserId() != actor.userId()
                    || gap.category() != input.category() || gap.source() != input.source()
                    || !Objects.equals(gap.description(), input.description())
                    || !KnowledgeGapInputs.CONSENT_VERSION.equals(gap.consentVersion())
                    || gap.state() != KnowledgeGapState.SUBMITTED || gap.version() != 0 || gap.assigneeUserId() != null
                    || gap.assignmentEpoch() != 0 || gap.pendingSolutionId() != null || !eventId.equals(gap.queueEventId())) {
                throw KnowledgeGapFailures.unavailable();
            }
            var event = new KnowledgeGapRows.Event(eventId, gap.id(), "GAP_CREATED", 0, actor.userId(),
                    null, 0, null, null, gap.createdAt());
            events.record(event, actor.userId());
            return commands.complete(requestId, new KnowledgeGapRows.Receipt(gap.id(), 0, KnowledgeGapState.SUBMITTED,
                    eventId, null, false));
        });
    }
}
