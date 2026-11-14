package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.service.KnowledgeGapAssigneeService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 人工分派、共享说明和负责人退回；回执不延续已经失去的处理关系。 */
@Service
@Transactional(timeout = 5)
public class KnowledgeGapAssignmentService {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapMapper mapper;
    private final KnowledgeGapCommandService commands;
    private final KnowledgeGapAssigneeService assignees;
    private final KnowledgeGapEventService events;

    public KnowledgeGapAssignmentService(KnowledgeGapAccess access, KnowledgeGapMapper mapper,
                                         KnowledgeGapCommandService commands, KnowledgeGapAssigneeService assignees,
                                         KnowledgeGapEventService events) {
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.assignees = assignees;
        this.events = events;
    }

    public KnowledgeGapRows.Receipt assign(long gapId, KnowledgeGapInputs.Assignment input, String requestId) {
        return write(gapId, KnowledgeGapAction.ASSIGN, input.expectedVersion(), input, requestId, input.assigneeUserId(), null);
    }

    public KnowledgeGapRows.Receipt note(long gapId, KnowledgeGapInputs.Message input, String requestId) {
        return write(gapId, KnowledgeGapAction.NOTE, input.expectedVersion(), input, requestId, null, input.text());
    }

    public KnowledgeGapRows.Receipt returnToReception(long gapId, KnowledgeGapInputs.Message input, String requestId) {
        return write(gapId, KnowledgeGapAction.RETURN, input.expectedVersion(), input, requestId, null, input.text());
    }

    private KnowledgeGapRows.Receipt write(long gapId, KnowledgeGapAction action, long expectedVersion, Object input,
                                           String requestId, Long assigneeUserId, String body) {
        return KnowledgeGapFailures.guarded(() -> {
            var actor = access.current();
            var replay = commands.claim(actor, action, gapId, expectedVersion, input, requestId);
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
            if (action == KnowledgeGapAction.ASSIGN) {
                assignees.requireEligible(assigneeUserId);
            }
            UUID eventId = UUID.randomUUID();
            var next = gap.workflow().advance(action, expectedVersion, assigneeUserId, null, eventId);
            if (mapper.advance(gapId, actor.userId(), expectedVersion, next) != 1) {
                throw ApiException.conflict("知识问题单状态已变化");
            }
            UUID entryId = null;
            if (body != null) {
                entryId = UUID.randomUUID();
                var entry = new KnowledgeGapRows.Entry(entryId, gapId, next.version(), action.name(), actor.userId(),
                        body, null, null, null);
                if (mapper.insertEntry(entry) != 1) {
                    throw KnowledgeGapFailures.unavailable();
                }
            }
            String kind = switch (action) {
                case ASSIGN -> "GAP_ASSIGNED";
                case NOTE -> "NOTE_ADDED";
                case RETURN -> "GAP_RETURNED";
                default -> throw KnowledgeGapFailures.unavailable();
            };
            Long eventAssignee = action == KnowledgeGapAction.RETURN ? gap.assigneeUserId() : next.assigneeUserId();
            events.record(new KnowledgeGapRows.Event(eventId, gapId, kind, next.version(), actor.userId(), eventAssignee,
                    next.assignmentEpoch(), entryId, null, null), gap.reporterUserId());
            return commands.complete(requestId, new KnowledgeGapRows.Receipt(gapId, next.version(), next.state(), eventId, null, false));
        });
    }
}
