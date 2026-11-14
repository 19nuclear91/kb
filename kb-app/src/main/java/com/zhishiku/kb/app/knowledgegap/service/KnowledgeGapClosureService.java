package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 撤回和关闭只停止处理，保留已分享记录，不冒充本人确认解决。 */
@Service
@Transactional(timeout = 5)
public class KnowledgeGapClosureService {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapMapper mapper;
    private final KnowledgeGapCommandService commands;
    private final KnowledgeGapEventService events;

    public KnowledgeGapClosureService(KnowledgeGapAccess access, KnowledgeGapMapper mapper,
                                      KnowledgeGapCommandService commands, KnowledgeGapEventService events) {
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.events = events;
    }

    public KnowledgeGapRows.Receipt withdraw(long gapId, KnowledgeGapInputs.Message input, String requestId) {
        return write(gapId, KnowledgeGapAction.WITHDRAW, input, requestId);
    }

    public KnowledgeGapRows.Receipt close(long gapId, KnowledgeGapInputs.Message input, String requestId) {
        return write(gapId, KnowledgeGapAction.CLOSE, input, requestId);
    }

    private KnowledgeGapRows.Receipt write(long gapId, KnowledgeGapAction action, KnowledgeGapInputs.Message input, String requestId) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            var replay = commands.claim(actor, action, gapId, input.expectedVersion(), input, requestId);
            if (replay != null) {
                return replay;
            }
            var gap = mapper.lock(gapId, actor.userId());
            if (gap == null) {
                throw ApiException.notFound("知识问题单不存在或不可访问");
            }
            if (gap.id() != gapId) {
                throw KnowledgeGapFailures.unavailable();
            }
            access.requireAction(actor, gap, action);
            UUID eventId = UUID.randomUUID();
            var next = gap.workflow().advance(action, input.expectedVersion(), null, null, eventId);
            if (mapper.advance(gapId, actor.userId(), input.expectedVersion(), next) != 1) {
                throw ApiException.conflict("知识问题单状态已变化");
            }
            UUID entryId = UUID.randomUUID();
            if (mapper.insertEntry(new KnowledgeGapRows.Entry(entryId, gapId, next.version(), action.name(), actor.userId(),
                    input.text(), null, null, null)) != 1) {
                throw KnowledgeGapFailures.unavailable();
            }
            events.record(new KnowledgeGapRows.Event(eventId, gapId, action == KnowledgeGapAction.CLOSE ? "GAP_CLOSED" : "GAP_WITHDRAWN",
                    next.version(), actor.userId(), next.assigneeUserId(), next.assignmentEpoch(), entryId, null, null), gap.reporterUserId());
            return commands.complete(requestId, new KnowledgeGapRows.Receipt(gapId, next.version(), next.state(), eventId, null, false));
        });
    }
}
