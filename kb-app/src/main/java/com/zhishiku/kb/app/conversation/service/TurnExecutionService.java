package com.zhishiku.kb.app.conversation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.aiusage.service.AiMultiObservation;
import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.BatchRow;
import com.zhishiku.kb.app.conversation.model.BeginResult;
import com.zhishiku.kb.app.conversation.model.CitationRow;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.ExecutionKey;
import com.zhishiku.kb.app.conversation.model.SettlementRow;
import com.zhishiku.kb.app.conversation.model.SourceRow;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.SourceDeclaration;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.conversation.UsageSettlement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 短事务轮次状态机；事务内只访问本地存储和权限事实。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class TurnExecutionService {
    private final ConversationMapper mapper;
    private final ConversationService conversations;
    private final ConversationIdentityGate identity;
    private final DocumentVersionAccessService documents;
    private final ConversationProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private AiMultiObservation observation;

    @Autowired
    public void setObservation(AiMultiObservation observation) { this.observation = observation; }

    @Autowired
    public TurnExecutionService(ConversationMapper mapper, ConversationService conversations,
                                ConversationIdentityGate identity, DocumentVersionAccessService documents,
                                ConversationProperties properties, ObjectMapper json) {
        this(mapper, conversations, identity, documents, properties, json, Clock.systemUTC());
    }

    public TurnExecutionService(ConversationMapper mapper, ConversationService conversations,
                                ConversationIdentityGate identity, DocumentVersionAccessService documents,
                                ConversationProperties properties, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.conversations = conversations;
        this.identity = identity;
        this.documents = documents;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    public BeginResult check(long user, long conversation, String question, String requestId, Long expected) {
        identity.require(user);
        validateQuestion(question, expected);
        var row = conversations.owned(user, conversation, false);
        var existing = mapper.request(conversation, ConversationService.requestId(requestId, null));
        return existing == null ? null : replay(existing, binding(question, expected));
    }

    @Transactional
    public BeginResult begin(long user, long conversation, String question, String requestId, Long expected) {
        identity.require(user);
        validateQuestion(question, expected);
        String request = ConversationService.requestId(requestId, null);
        var row = conversations.owned(user, conversation, true);
        var existing = mapper.request(conversation, request);
        if (existing != null) return replay(existing, binding(question, expected));
        if (row.getActiveTurnId() != null || row.getLastTurnSeq() != expected || expected == Long.MAX_VALUE)
            throw ConversationService.conflict();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var turn = new TurnRow();
        turn.setId(UUID.randomUUID());
        turn.setConversationId(conversation);
        turn.setTurnSeq(expected + 1);
        turn.setRequestId(request);
        turn.setBindingDigest(binding(question, expected));
        turn.setQuestion(question);
        turn.setAssistantText("");
        turn.setStatus("RUNNING");
        turn.setUsageStatus("NOT_INVOKED");
        turn.setExecutionEpoch(row.getExecutionEpoch());
        turn.setTicketHash(RequestSignature.digest(ticket));
        turn.setExecutionTicketDigest(turn.getTicketHash());
        turn.setLeaseUntil(clock.instant().plusSeconds(properties.getLeaseSeconds()));
        turn.setCreatedAt(clock.instant());
        turn.setUpdatedAt(clock.instant());
        ConversationService.checkWrite(mapper.insertTurn(turn));
        row.setLastTurnSeq(turn.getTurnSeq());
        row.setActiveTurnId(turn.getId());
        row.setUpdatedAt(clock.instant());
        ConversationService.checkWrite(mapper.updateConversation(row));
        if (observation != null) observation.begin(user, turn);
        return new BeginResult(conversation, turn.getId(), turn.getTurnSeq(), "RUNNING", false,
                new ExecutionKey(conversation, turn.getId(), user, row.getExecutionEpoch(), ticket));
    }

    private BeginResult replay(TurnRow row, String digest) {
        if (!RequestSignature.constantEquals(row.getBindingDigest(), digest)) throw ConversationService.conflict();
        return new BeginResult(row.getConversationId(), row.getId(), row.getTurnSeq(), row.getStatus(), true, null);
    }

    private void validateQuestion(String question, Long expected) {
        if (question == null || question.isBlank() || ConversationService.length(question) > properties.getMaxQuestionCodepoints()
                || expected == null || expected < 0 || !validUnicode(question)) throw ConversationService.invalid();
    }

    private static String binding(String question, long expected) {
        return RequestSignature.digest(expected + "\\n" + question);
    }

    @Transactional
    public void seal(long user, ExecutionKey key, String kind, List<UUID> parents, List<CitationView> citations) {
        var handle = identity.require(user);
        if (user != key.ownerUserId() || parents == null || citations == null
                || parents.size() > properties.getHistoryPairs() || citations.size() > properties.getMaxSources())
            throw ConversationService.invalid();
        var conversation = conversations.owned(user, key.conversationId(), true);
        var turn = qualified(conversation, key, true);
        if (!Set.of("MODEL", "NO_RESULTS").contains(kind)) throw ConversationService.invalid();
        String sealDigest;
        try {
            sealDigest = RequestSignature.sha256(json.writeValueAsBytes(Map.of("kind", kind, "parents", parents,
                    "citations", citations)));
        } catch (JsonProcessingException error) {
            throw ConversationService.invalid();
        }
        if (turn.isSourceSealed()) {
            if (!sealDigest.equals(turn.getSealDigest())) throw ConversationService.conflict();
            if (!conversations.visibleDependencies(handle, List.of(turn)).containsKey(turn.getId()))
                throw ApiException.forbidden("来源声明当前不可使用");
            return;
        }
        if ("NO_RESULTS".equals(kind) && (!parents.isEmpty() || !citations.isEmpty()))
            throw ConversationService.invalid();
        if ("MODEL".equals(kind) && citations.isEmpty()) throw ConversationService.invalid();
        if (new HashSet<>(parents).size() != parents.size()) throw ConversationService.invalid();
        List<TurnRow> parentRows = new ArrayList<>();
        for (UUID parent : parents) {
            var row = mapper.turn(parent);
            if (row == null || row.getConversationId() != key.conversationId() || !"COMPLETED".equals(row.getStatus())
                    || row.getTurnSeq() >= turn.getTurnSeq()) throw ConversationService.conflict();
            parentRows.add(row);
        }
        var inherited = conversations.visibleDependencies(handle, parentRows);
        if (inherited.size() != parentRows.size()) throw ApiException.forbidden("历史上下文当前不可使用");
        Set<SourceKey> sources = new HashSet<>();
        inherited.values().forEach(sources::addAll);
        Set<SourceKey> fresh = new HashSet<>();
        for (var citation : citations) {
            if (citation == null || citation.chunkId() <= 0 || citation.title() == null
                    || ConversationService.length(citation.title()) > 2000 || !boundedMetadata(citation))
                throw ConversationService.invalid();
            try {
                fresh.add(new SourceKey(citation.documentId(), citation.versionId()));
            } catch (IllegalArgumentException error) {
                throw ConversationService.invalid();
            }
        }
        if (!documents.visible(handle, fresh).containsAll(fresh)) throw ApiException.forbidden("新材料当前不可使用");
        sources.addAll(fresh);
        if (sources.size() > properties.getMaxSources()) throw ConversationService.invalid();
        for (var source : sources) {
            var row = new SourceRow();
            row.setTurnId(turn.getId());
            row.setDocumentId(source.documentId());
            row.setVersionId(source.versionId());
            ConversationService.checkWrite(mapper.insertSource(row));
        }
        for (int i = 0; i < citations.size(); i++)
            ConversationService.checkWrite(mapper.insertCitation(citation(turn.getId(), i, citations.get(i))));
        turn.setAnswerKind(kind);
        turn.setSourceSealed(true);
        turn.setExpectedSourceCount(sources.size());
        turn.setSourceDigest(SourceDeclaration.digest(sources));
        turn.setSealDigest(sealDigest);
        save(turn);
    }

    private boolean boundedMetadata(CitationView view) {
        for (String value : new String[]{view.headingPath(), view.structureType(), view.sourceLocator()}) {
            if (value != null && (ConversationService.length(value) > 2000 || !validUnicode(value))) return false;
        }
        return validUnicode(view.title());
    }

    private CitationRow citation(UUID turn, int ordinal, CitationView v) {
        var r = new CitationRow();
        r.setTurnId(turn);
        r.setOrdinal(ordinal);
        r.setDocumentId(v.documentId());
        r.setVersionId(v.versionId());
        r.setChunkId(v.chunkId());
        r.setTitle(v.title());
        r.setVersionNo(v.versionNo());
        r.setPageNo(v.pageNo());
        r.setHeadingPath(v.headingPath());
        r.setStructureType(v.structureType());
        r.setSourceLocator(v.sourceLocator());
        r.setSourceLineStart(v.sourceLineStart());
        r.setSourceLineEnd(v.sourceLineEnd());
        r.setSourceCharStart(v.sourceCharStart());
        r.setSourceCharEnd(v.sourceCharEnd());
        r.setContextTruncated(v.contextTruncated());
        return r;
    }

    @Transactional
    public long append(ExecutionKey key, long sequence, String text) {
        var conversation = conversations.owned(key.ownerUserId(), key.conversationId(), true);
        var turn = qualified(conversation, key, true);
        if (!turn.isSourceSealed() || sequence < 1 || text == null || text.isEmpty()
                || ConversationService.length(text) > properties.getBatchCodepoints() || !validUnicode(text))
            throw ConversationService.invalid();
        String digest = RequestSignature.digest(text);
        var old = mapper.batch(turn.getId(), sequence);
        if (old != null) {
            if (!digest.equals(old.getDigest())) throw ConversationService.conflict();
            return turn.getLastBatchSeq();
        }
        if (sequence != turn.getLastBatchSeq() + 1) throw ConversationService.conflict();
        int size = ConversationService.length(text);
        if ((long) turn.getSavedCodepoints() + size > properties.getMaxAnswerCodepoints())
            throw ConversationService.invalid();
        String updated = (turn.getAssistantText() == null ? "" : turn.getAssistantText()) + text;
        if ("NO_RESULTS".equals(turn.getAnswerKind()) && !ConversationService.NO_RESULTS.startsWith(updated))
            throw ConversationService.invalid();
        var batch = new BatchRow();
        batch.setTurnId(turn.getId());
        batch.setBatchSeq(sequence);
        batch.setDigest(digest);
        batch.setCodepoints(size);
        ConversationService.checkWrite(mapper.insertBatch(batch));
        turn.setAssistantText(updated);
        turn.setLastBatchSeq(sequence);
        turn.setSavedCodepoints(turn.getSavedCodepoints() + size);
        save(turn);
        return sequence;
    }

    @Transactional
    public void renew(ExecutionKey key) {
        var conversation = conversations.owned(key.ownerUserId(), key.conversationId(), true);
        var turn = qualified(conversation, key, true);
        turn.setLeaseUntil(clock.instant().plusSeconds(properties.getLeaseSeconds()));
        save(turn);
    }

    @Transactional
    public void reserve(ExecutionKey key, long department, UUID lease, UUID event, long reserved) {
        var conversation = conversations.owned(key.ownerUserId(), key.conversationId(), true);
        var turn = qualified(conversation, key, true);
        if (department <= 0 || lease == null || event == null || reserved != properties.getReservationTokens())
            throw ConversationService.invalid();
        var old = mapper.settlement(turn.getId());
        if (old != null) {
            if (old.getDepartmentId() != department || !old.getLeaseId().equals(lease) || !old.getEventId().equals(event)
                    || old.getReservedTokens() != reserved) throw ConversationService.conflict();
            return;
        }
        var settlement = new SettlementRow();
        settlement.setTurnId(turn.getId());
        settlement.setUserId(key.ownerUserId());
        settlement.setDepartmentId(department);
        settlement.setLeaseId(lease);
        settlement.setEventId(event);
        settlement.setReservedTokens(reserved);
        settlement.setUsageStatus("NOT_INVOKED");
        settlement.setState("RESERVED");
        settlement.setCreatedAt(clock.instant());
        ConversationService.checkWrite(mapper.insertSettlement(settlement));
        if (observation != null) observation.reserved(settlement);
    }

    @Transactional
    public void dispatch(ExecutionKey key) {
        var conversation = conversations.owned(key.ownerUserId(), key.conversationId(), true);
        var turn = qualified(conversation, key, true);
        if (!turn.isSourceSealed() || !"MODEL".equals(turn.getAnswerKind()) || mapper.settlement(turn.getId()) == null)
            throw ConversationService.conflict();
        turn.setModelDispatchIntent(true);
        turn.setUsageStatus("UNKNOWN");
        turn.setPromptTokens(null);
        turn.setCompletionTokens(null);
        save(turn);
        if (observation != null) observation.dispatched(turn);
    }

    @Transactional
    public SettlementRow finish(ExecutionKey key, String status, long lastBatch, boolean finalUsage, Long prompt,
                                Long completion) {
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) throw ConversationService.invalid();
        var conversation = conversations.owned(key.ownerUserId(), key.conversationId(), true);
        var turn = qualified(conversation, key, false);
        if (!"RUNNING".equals(turn.getStatus())) {
            if (!turn.getStatus().equals(status)) throw ConversationService.conflict();
            var old = mapper.settlement(turn.getId());
            if (old != null) {
                var usage = UsageSettlement.calculate(turn.isModelDispatchIntent(), finalUsage, prompt, completion,
                        old.getReservedTokens());
                if (!intent(old, status, usage).equals(old.getIntentDigest())) throw ConversationService.conflict();
            }
            if (lastBatch != turn.getLastBatchSeq()) throw ConversationService.conflict();
            return old;
        }
        qualified(conversation, key, true);
        if (lastBatch != turn.getLastBatchSeq() || ("COMPLETED".equals(status) && (!turn.isSourceSealed()
                || ("NO_RESULTS".equals(turn.getAnswerKind()) && !ConversationService.NO_RESULTS.equals(turn.getAssistantText())))))
            throw ConversationService.conflict();
        return close(conversation, turn, status, finalUsage, prompt, completion);
    }

    private SettlementRow close(ConversationRow conversation, TurnRow turn, String status, boolean finalUsage,
                                Long prompt, Long completion) {
        return close(conversation, turn, status, finalUsage, prompt, completion, true);
    }

    private SettlementRow close(ConversationRow conversation, TurnRow turn, String status, boolean finalUsage,
                                Long prompt, Long completion, boolean observe) {
        var settlement = mapper.settlement(turn.getId());
        var usage = UsageSettlement.calculate(turn.isModelDispatchIntent(), finalUsage, prompt, completion,
                settlement == null ? properties.getReservationTokens() : settlement.getReservedTokens());
        if (turn.isModelDispatchIntent() && settlement == null) throw ApiException.unavailable("轮次账本不完整");
        turn.setStatus(status);
        turn.setTicketHash(null);
        turn.setLeaseUntil(null);
        turn.setFinishedAt(clock.instant());
        turn.setUsageStatus(usage.status());
        turn.setPromptTokens(usage.promptTokens());
        turn.setCompletionTokens(usage.completionTokens());
        save(turn);
        if (settlement != null) {
            settlement.setUsageStatus(usage.status());
            settlement.setPromptTokens(usage.promptTokens());
            settlement.setCompletionTokens(usage.completionTokens());
            settlement.setChargedTokens(usage.chargedTokens());
            settlement.setGenerationStatus(status);
            settlement.setFinalizedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
            settlement.setIntentDigest(intent(settlement, status, usage));
            settlement.setState("PENDING");
            settlement.setNextAttemptAt(clock.instant());
            ConversationService.checkWrite(mapper.updateSettlement(settlement));
        }
        conversation.setActiveTurnId(null);
        conversation.setUpdatedAt(clock.instant());
        ConversationService.checkWrite(mapper.updateConversation(conversation));
        if (observe && observation != null) observation.finished(turn, settlement);
        return settlement;
    }

    private String intent(SettlementRow row, String status, UsageSettlement.Value usage) {
        return UsageSettlement.digest(row.getTurnId().toString(), row.getFinalizedAt().toString(),
                row.getLeaseId().toString(), row.getEventId().toString(), row.getUserId(),
                row.getDepartmentId(), row.getReservedTokens(), status, usage);
    }

    @Transactional
    public void recover(long id) {
        var conversation = mapper.lock(id);
        if (conversation == null || conversation.getDeletedAt() != null || conversation.getActiveTurnId() == null)
            return;
        var turn = mapper.lockTurn(conversation.getActiveTurnId());
        if (turn != null && "RUNNING".equals(turn.getStatus()) && !turn.getLeaseUntil().isAfter(clock.instant())) {
            close(conversation, turn, "INTERRUPTED", false, null, null);
        }
    }

    @Transactional
    public void delete(long user, long id) {
        identity.require(user);
        var conversation = mapper.lock(id);
        if (conversation == null || conversation.getOwnerUserId() != user) throw ConversationService.missing();
        if (conversation.getDeletedAt() != null) return;
        TurnRow closedTurn = null;
        if (conversation.getActiveTurnId() != null) {
            var turn = mapper.lockTurn(conversation.getActiveTurnId());
            if (turn == null || !"RUNNING".equals(turn.getStatus())) throw ConversationService.conflict();
            close(conversation, turn, "CANCELLED", false, null, null, false);
            closedTurn = turn;
        }
        mapper.clearCitations(id);
        mapper.clearSources(id);
        mapper.scrubTurns(id);
        conversation.setTitle(null);
        conversation.setDeletedAt(clock.instant());
        conversation.setUpdatedAt(clock.instant());
        conversation.setActiveTurnId(null);
        conversation.setExecutionEpoch(Math.addExact(conversation.getExecutionEpoch(), 1));
        ConversationService.checkWrite(mapper.updateConversation(conversation));
        if (closedTurn != null && observation != null) observation.finished(closedTurn, mapper.settlement(closedTurn.getId()));
    }

    private TurnRow qualified(ConversationRow conversation, ExecutionKey key, boolean running) {
        if (key.ticket() == null || key.ticket().length() > 64 || key.turnId() == null)
            throw ConversationService.conflict();
        var turn = mapper.lockTurn(key.turnId());
        if (turn == null || turn.getConversationId() != conversation.getId()
                || key.executionEpoch() != conversation.getExecutionEpoch() || turn.getExecutionEpoch() != key.executionEpoch()
                || !RequestSignature.constantEquals(turn.getExecutionTicketDigest(),
                RequestSignature.digest(key.ticket())))
            throw ConversationService.conflict();
        if (running && (!"RUNNING".equals(turn.getStatus()) || !turn.getId().equals(conversation.getActiveTurnId())
                || turn.getLeaseUntil() == null || !turn.getLeaseUntil().isAfter(clock.instant())
                || !RequestSignature.constantEquals(turn.getTicketHash(), RequestSignature.digest(key.ticket()))))
            throw ConversationService.conflict();
        return turn;
    }

    @Transactional
    public boolean ackSettlement(UUID turn, String digest) {
        var row = mapper.settlement(turn);
        if (row == null || !RequestSignature.constantEquals(row.getIntentDigest(), digest))
            throw ConversationService.conflict();
        if ("SETTLED".equals(row.getState())) return true;
        if (!"PENDING".equals(row.getState())) return false;
        boolean accepted = mapper.ackSettlement(turn, digest, clock.instant()) == 1;
        if (accepted && observation != null) {
            row.setState("SETTLED"); row.setSettledAt(clock.instant()); observation.settled(row);
        }
        return accepted;
    }

    private void save(TurnRow turn) {
        turn.setUpdatedAt(clock.instant());
        ConversationService.checkWrite(mapper.updateTurn(turn));
    }

    public static boolean validUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}

