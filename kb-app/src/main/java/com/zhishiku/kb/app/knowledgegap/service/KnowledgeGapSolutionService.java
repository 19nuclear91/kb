package com.zhishiku.kb.app.knowledgegap.service;

import com.zhishiku.kb.app.auth.service.KnowledgeGapParticipantAccess;
import com.zhishiku.kb.app.document.service.PublishedDocumentQueryService;
import com.zhishiku.kb.app.knowledgegap.mapper.KnowledgeGapMapper;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapAction;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapInputs;
import com.zhishiku.kb.app.knowledgegap.model.KnowledgeGapRows;
import com.zhishiku.kb.common.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 当前负责人提交固定方案，原提问者明确确认或退回；资料鉴权不由问题资格替代。 */
@Service
@Transactional(timeout = 5)
public class KnowledgeGapSolutionService {
    private final KnowledgeGapAccess access;
    private final KnowledgeGapMapper mapper;
    private final KnowledgeGapCommandService commands;
    private final KnowledgeGapEventService events;
    private final PublishedDocumentQueryService documents;
    private final KnowledgeGapParticipantAccess participants;

    public KnowledgeGapSolutionService(KnowledgeGapAccess access, KnowledgeGapMapper mapper,
                                       KnowledgeGapCommandService commands, KnowledgeGapEventService events,
                                       PublishedDocumentQueryService documents, KnowledgeGapParticipantAccess participants) {
        this.access = access;
        this.mapper = mapper;
        this.commands = commands;
        this.events = events;
        this.documents = documents;
        this.participants = participants;
    }

    public KnowledgeGapRows.Receipt submit(long gapId, KnowledgeGapInputs.Solution input, String requestId) {
        return write(gapId, KnowledgeGapAction.SOLUTION, input.expectedVersion(), input, requestId);
    }

    public KnowledgeGapRows.Receipt confirm(long gapId, KnowledgeGapInputs.Confirmation input, String requestId) {
        return write(gapId, KnowledgeGapAction.CONFIRM, input.expectedVersion(), input, requestId);
    }

    public KnowledgeGapRows.Receipt reject(long gapId, KnowledgeGapInputs.Rejection input, String requestId) {
        return write(gapId, KnowledgeGapAction.REJECT_SOLUTION, input.expectedVersion(), input, requestId);
    }

    private KnowledgeGapRows.Receipt write(long gapId, KnowledgeGapAction action, long expectedVersion,
                                           Object input, String requestId) {
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
            UUID eventId = UUID.randomUUID();
            UUID solutionId = action == KnowledgeGapAction.SOLUTION ? UUID.randomUUID()
                    : action == KnowledgeGapAction.CONFIRM ? ((KnowledgeGapInputs.Confirmation) input).solutionId()
                    : ((KnowledgeGapInputs.Rejection) input).solutionId();
            var next = gap.workflow().advance(action, expectedVersion, null, solutionId, eventId);
            UUID entryId = null;
            KnowledgeGapRows.Entry entry = null;
            if (action == KnowledgeGapAction.SOLUTION) {
                var solution = (KnowledgeGapInputs.Solution) input;
                if (solution.documentId() != null) {
                    var reporter = participants.active(gap.reporterUserId());
                    if (documents.reference(actor, solution.documentId(), solution.versionId()).isEmpty()
                            || documents.reference(reporter, solution.documentId(), solution.versionId()).isEmpty()) {
                        throw ApiException.conflict("方案资料当前不可用");
                    }
                }
                entryId = solutionId;
                entry = new KnowledgeGapRows.Entry(solutionId, gapId, next.version(), "SOLUTION", actor.userId(),
                        solution.text(), solution.documentId(), solution.versionId(), null);
            } else if (action == KnowledgeGapAction.CONFIRM) {
                var solution = mapper.entry(gapId, solutionId, actor.userId());
                if (solution == null || solution.gapId() != gapId || !solutionId.equals(solution.entryId())
                        || !"SOLUTION".equals(solution.kind())
                        || (solution.documentId() == null) != (solution.versionId() == null)) {
                    throw KnowledgeGapFailures.unavailable();
                }
                if (solution.documentId() != null && documents.reference(actor, solution.documentId(), solution.versionId()).isEmpty()) {
                    throw ApiException.conflict("方案资料当前不可用");
                }
            } else {
                var rejection = (KnowledgeGapInputs.Rejection) input;
                entryId = UUID.randomUUID();
                entry = new KnowledgeGapRows.Entry(entryId, gapId, next.version(), "REJECT_SOLUTION", actor.userId(),
                        rejection.reason(), null, null, null);
            }
            if (mapper.advance(gapId, actor.userId(), expectedVersion, next) != 1) {
                throw ApiException.conflict("知识问题单状态已变化");
            }
            if (entry != null && mapper.insertEntry(entry) != 1) {
                throw KnowledgeGapFailures.unavailable();
            }
            String kind = action == KnowledgeGapAction.SOLUTION ? "GAP_SOLUTION_SUBMITTED"
                    : action == KnowledgeGapAction.CONFIRM ? "GAP_RESOLVED" : "GAP_SOLUTION_REJECTED";
            events.record(new KnowledgeGapRows.Event(eventId, gapId, kind, next.version(), actor.userId(),
                    next.assigneeUserId(), next.assignmentEpoch(), entryId, solutionId, null), gap.reporterUserId());
            return commands.complete(requestId, new KnowledgeGapRows.Receipt(gapId, next.version(), next.state(),
                    eventId, solutionId, false));
        });
    }
}
