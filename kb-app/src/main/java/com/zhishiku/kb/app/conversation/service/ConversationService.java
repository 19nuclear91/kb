package com.zhishiku.kb.app.conversation.service;

import com.zhishiku.kb.app.auth.service.ConversationIdentityGate;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.conversation.mapper.ConversationMapper;
import com.zhishiku.kb.app.conversation.model.CitationRow;
import com.zhishiku.kb.app.conversation.model.CitationView;
import com.zhishiku.kb.app.conversation.model.ConversationPage;
import com.zhishiku.kb.app.conversation.model.ConversationRow;
import com.zhishiku.kb.app.conversation.model.ConversationView;
import com.zhishiku.kb.app.conversation.model.CreationResult;
import com.zhishiku.kb.app.conversation.model.SourceRow;
import com.zhishiku.kb.app.conversation.model.TurnPage;
import com.zhishiku.kb.app.conversation.model.TurnRow;
import com.zhishiku.kb.app.conversation.model.TurnView;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.conversation.RequestSignature;
import com.zhishiku.kb.common.conversation.SourceDeclaration;
import com.zhishiku.kb.common.conversation.SourceKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 私有会话读取；每次请求重新取得主体，屏蔽整体回答。
 */
@Service
@ConditionalOnProperty(prefix = "kb.conversation", name = "enabled", havingValue = "true")
public class ConversationService {
    public static final String NO_RESULTS = "未找到与问题相关的内容。";
    public static final String BLOCKED = "该回答当前不可查看。";
    private final ConversationMapper mapper;
    private final ConversationIdentityGate identity;
    private final DocumentVersionAccessService documents;
    private final ConversationProperties properties;
    private final Clock clock;

    @Autowired
    public ConversationService(ConversationMapper mapper, ConversationIdentityGate identity,
                               DocumentVersionAccessService documents, ConversationProperties properties) {
        this(mapper, identity, documents, properties, Clock.systemUTC());
    }

    public ConversationService(ConversationMapper mapper, ConversationIdentityGate identity,
                               DocumentVersionAccessService documents, ConversationProperties properties, Clock clock) {
        this.mapper = mapper;
        this.identity = identity;
        this.documents = documents;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public CreationResult create(long user, String requestedTitle, String requestId, String headerId) {
        identity.require(user);
        String request = requestId(requestId, headerId);
        String title = requestedTitle == null ? "新会话" : requestedTitle.strip();
        if (title.isEmpty() || length(title) > 200 || !TurnExecutionService.validUnicode(title)) throw invalid();
        String digest = RequestSignature.digest(title);
        var existing = mapper.findCreation(user, request);
        if (existing != null) return creationReplay(existing, digest);
        var row = new ConversationRow();
        row.setOwnerUserId(user);
        row.setTitle(title);
        row.setCreateRequestId(request);
        row.setCreationDigest(digest);
        row.setCreatedAt(clock.instant());
        row.setUpdatedAt(clock.instant());
        int inserted = mapper.insertConversation(row);
        if (inserted == 0) return creationReplay(mapper.findCreation(user, request), digest);
        checkWrite(inserted);
        if (row.getId() == null) throw ApiException.unavailable("会话保存失败");
        return new CreationResult(view(row), false);
    }

    private CreationResult creationReplay(ConversationRow row, String digest) {
        if (row == null) throw ApiException.unavailable("会话保存失败");
        if (row.getDeletedAt() != null) throw missing();
        if (!RequestSignature.constantEquals(digest, row.getCreationDigest())) throw conflict();
        return new CreationResult(view(row), true);
    }

    public ConversationPage list(long user, Integer requestedLimit, String cursor) {
        identity.require(user);
        int limit = limit(requestedLimit);
        Instant before = null;
        Long beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 256) throw new IllegalArgumentException();
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(
                        "\\|", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                before = Instant.parse(parts[0]);
                beforeId = Long.valueOf(parts[1]);
                if (beforeId <= 0) throw new IllegalArgumentException();
            } catch (RuntimeException error) {
                throw invalid();
            }
        }
        var rows = mapper.list(user, before, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        String next = null;
        if (more) {
            var last = page.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.getCreatedAt() + "|" + last.getId()).getBytes(StandardCharsets.UTF_8));
        }
        return new ConversationPage(page.stream().map(ConversationService::view).toList(), next);
    }

    public ConversationView detail(long user, long id) {
        identity.require(user);
        return view(owned(user, id, false));
    }

    public TurnPage turns(long user, long id, Integer requestedLimit, Long cursor) {
        var handle = identity.require(user);
        owned(user, id, false);
        int limit = limit(requestedLimit);
        if (cursor != null && cursor < 0) throw invalid();
        var rows = mapper.turns(id, cursor == null ? 0 : cursor, limit + 1);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        return new TurnPage(project(handle, page), more ? page.getLast().getTurnSeq() : null);
    }

    public TurnView turn(long user, long id, UUID turn) {
        var handle = identity.require(user);
        owned(user, id, false);
        var row = mapper.turn(turn);
        if (row == null || row.getConversationId() != id) throw missing();
        return project(handle, List.of(row)).getFirst();
    }

    public List<TurnView> prepare(long user, long id) {
        var handle = identity.require(user);
        owned(user, id, false);
        return project(handle, mapper.history(id, properties.getHistoryPairs())).stream()
                .filter(v -> "COMPLETED".equals(v.status()) && "VISIBLE".equals(v.visibility())).toList();
    }

    public List<Map<String, Object>> prepareContext(long user, long id) {
        var handle = identity.require(user);
        owned(user, id, false);
        var rows = mapper.history(id, properties.getHistoryPairs());
        var dependencies = visibleDependencies(handle, rows);
        return rows.stream().filter(row -> "COMPLETED".equals(row.getStatus())
                        && dependencies.containsKey(row.getId()) && row.getQuestion() != null && row.getAssistantText() != null)
                .map(row -> Map.<String, Object>of("id", row.getId(), "turnSeq", row.getTurnSeq(),
                        "question", row.getQuestion(), "answer", row.getAssistantText(),
                        "sources", dependencies.get(row.getId()).stream().sorted().toList())).toList();
    }

    public Map<UUID, Set<SourceKey>> visibleDependencies(PermissionService.PrincipalHandle handle, List<TurnRow> rows) {
        Map<UUID, Set<SourceKey>> valid = new HashMap<>();
        Set<SourceKey> union = new HashSet<>();
        for (var row : rows) {
            var sources = declaration(row);
            if (sources != null) {
                valid.put(row.getId(), sources);
                union.addAll(sources);
            }
        }
        var visible = documents.visible(handle, union);
        valid.entrySet().removeIf(e -> !visible.containsAll(e.getValue()));
        return valid;
    }

    private List<TurnView> project(PermissionService.PrincipalHandle handle, List<TurnRow> rows) {
        var valid = visibleDependencies(handle, rows);
        List<TurnView> views = new ArrayList<>();
        for (var row : rows) {
            boolean allowed = valid.containsKey(row.getId());
            var settlement = mapper.settlement(row.getId());
            views.add(new TurnView(row.getId(), row.getTurnSeq(), row.getQuestion(), allowed ?
                    row.getAssistantText() : null,
                    row.getStatus(), row.getAnswerKind(), allowed ? "VISIBLE" : "BLOCKED", allowed ? null : BLOCKED,
                    allowed ? mapper.citations(row.getId()).stream().map(ConversationService::citation).toList() :
                            List.of(),
                    row.getLastBatchSeq(), allowed ? row.getSavedCodepoints() : 0, row.getUsageStatus(),
                    row.getPromptTokens(),
                    row.getCompletionTokens(), settlement == null ? null : settlement.getState(),
                    settlement == null ? null : settlement.getChargedTokens(), row.getCancellationReason()));
        }
        return views;
    }

    public Set<SourceKey> declaration(TurnRow row) {
        if (!row.isSourceSealed() || row.getSourceDigest() == null || row.getExpectedSourceCount() < 0) return null;
        var stored = mapper.sources(row.getId());
        Set<SourceKey> sources = new HashSet<>();
        try {
            for (SourceRow source : stored) {
                if (!row.getId().equals(source.getTurnId()) || !sources.add(new SourceKey(source.getDocumentId(),
                        source.getVersionId())))
                    return null;
            }
        } catch (RuntimeException error) {
            return null;
        }
        if (sources.size() != row.getExpectedSourceCount() || sources.size() > properties.getMaxSources()
                || !SourceDeclaration.digest(sources).equals(row.getSourceDigest())) return null;
        if ("MODEL".equals(row.getAnswerKind())) return sources.isEmpty() ? null : sources;
        if ("NO_RESULTS".equals(row.getAnswerKind()) && sources.isEmpty()
                && (row.getAssistantText() == null || NO_RESULTS.startsWith(row.getAssistantText()))
                && (!"COMPLETED".equals(row.getStatus()) || NO_RESULTS.equals(row.getAssistantText()))) return sources;
        return null;
    }

    public ConversationRow owned(long user, long id, boolean lock) {
        if (id <= 0) throw invalid();
        var row = lock ? mapper.lock(id) : mapper.find(id);
        if (row == null || row.getOwnerUserId() != user || row.getDeletedAt() != null) throw missing();
        return row;
    }

    public static String requestId(String body, String header) {
        String first = body == null ? null : body.strip(), second = header == null ? null : header.strip();
        if (first != null && second != null && !first.equals(second)) throw invalid();
        String chosen = first == null ? second : first;
        if (chosen == null || length(chosen) < 1 || length(chosen) > 128 || !TurnExecutionService.validUnicode(chosen)
                || chosen.chars().anyMatch(Character::isISOControl)) throw invalid();
        return chosen;
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    public static void checkWrite(int count) {
        if (count != 1) throw ApiException.unavailable("会话保存失败");
    }

    public static ApiException invalid() {
        return ApiException.badRequest("会话请求参数无效");
    }

    public static ApiException missing() {
        return ApiException.notFound("会话或轮次不可访问");
    }

    public static ApiException conflict() {
        return ApiException.conflict("会话请求存在冲突");
    }

    private static int limit(Integer requested) {
        int limit = requested == null ? 20 : requested;
        if (limit < 1 || limit > 100) throw invalid();
        return limit;
    }

    public static ConversationView view(ConversationRow r) {
        return new ConversationView(r.getId(), r.getTitle(), r.getCreatedAt(), r.getUpdatedAt(), r.getLastTurnSeq(),
                r.getActiveTurnId());
    }

    public static CitationView citation(CitationRow r) {
        return new CitationView(r.getDocumentId(), r.getVersionId(), r.getChunkId(), r.getTitle(), r.getVersionNo(),
                r.getPageNo(), r.getHeadingPath(), r.getStructureType(), r.getSourceLocator(), r.getSourceLineStart()
                , r.getSourceLineEnd(), r.getSourceCharStart(), r.getSourceCharEnd(), r.isContextTruncated());
    }
}

