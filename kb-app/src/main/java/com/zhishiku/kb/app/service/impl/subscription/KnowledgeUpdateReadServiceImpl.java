package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.document.changenote.service.ChangeNoteReadService;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeQuery;
import com.zhishiku.kb.app.domain.dto.subscription.KnowledgeRows;
import com.zhishiku.kb.app.domain.vo.subscription.KnowledgeViews;
import com.zhishiku.kb.app.domain.vo.subscription.SubscriptionViews;
import com.zhishiku.kb.app.mapper.subscription.KnowledgeQueryMapper;
import com.zhishiku.kb.app.service.subscription.KnowledgeUpdateReadService;
import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;
import com.zhishiku.kb.common.security.PermissionScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 查询先授权再分页，说明读取保持固定目标，不以当前版本替代历史发布。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
public class KnowledgeUpdateReadServiceImpl implements KnowledgeUpdateReadService {
    private final SubscriptionAccess access;
    private final KnowledgeQueryMapper mapper;
    private final ChangeNoteReadService notes;

    @Override
    public SubscriptionViews.Page<KnowledgeViews.Update> updates(long actor, LocalDate week, int limit, String cursor) {
        SubscriptionRules.limit(limit);
        if (week != null) {
            KnowledgeCalendar.parse(week.toString());
        }
        String context = SubscriptionCursor.context(actor, "knowledge-updates", week == null ? null : week.toString());
        String position = SubscriptionCursor.decode(cursor, context);
        var query = query(access.current(actor), null, null, null, week,
                position == null ? null : SubscriptionRules.number(position, 1), null, null, limit + 1);
        return page(mapper.updates(query), limit, context, row -> Long.toString(row.position()), this::update);
    }

    @Override
    public KnowledgeViews.Detail detail(long actor, UUID updateId) {
        var scope = access.current(actor);
        var row = mapper.update(query(scope, updateId, null, null, null, null, null, null, 1));
        if (row == null) {
            throw SubscriptionFailure.hidden();
        }
        var note = notes.get(actor, row.documentId(), row.versionId());
        var base = note.hasNote() && note.baseVersionId() == null ? null
                : mapper.base(query(scope, updateId, null, row.documentId(), null, null, null, null, 1), row.versionId(), note.baseVersionId());
        String status = base != null ? "BASE_AVAILABLE" : note.baseVersionId() == null ? "NO_BASE" : "BASE_UNAVAILABLE";
        return new KnowledgeViews.Detail(update(row), note.hasNote(), note.text(), Long.toString(note.noteVersion()),
                note.corrected(), note.createdAfterVisible(), base == null ? null : Long.toString(base.versionId()),
                base == null ? null : base.versionNo(), status);
    }

    @Override
    public SubscriptionViews.Page<KnowledgeViews.Digest> digests(long actor, int limit, String cursor) {
        SubscriptionRules.limit(limit);
        String context = SubscriptionCursor.context(actor, "knowledge-digests");
        String position = SubscriptionCursor.decode(cursor, context);
        var query = query(access.current(actor), null, null, null, null, null, null,
                KnowledgeCalendar.parse(position), limit + 1);
        return page(mapper.digests(query), limit, context, row -> row.weekStart().toString(), this::digest);
    }

    @Override
    public KnowledgeViews.DigestDetail digest(long actor, UUID digestId, int limit, String cursor) {
        SubscriptionRules.limit(limit);
        var scope = access.current(actor);
        var digest = requireDigest(scope, digestId);
        String context = SubscriptionCursor.context(actor, "knowledge-digest-groups", digestId.toString());
        String position = SubscriptionCursor.decode(cursor, context);
        Long before = null;
        Long document = null;
        if (position != null) {
            String[] values = position.split(":", -1);
            if (values.length != 2) {
                throw SubscriptionFailure.invalid();
            }
            before = SubscriptionRules.number(values[0], 1);
            document = SubscriptionRules.number(values[1], 1);
        }
        var query = query(scope, null, digestId, null, null, before, document, null, limit + 1);
        var groups = page(mapper.groups(query), limit, context, row -> row.position() + ":" + row.documentId(),
                row -> new KnowledgeViews.Group(Long.toString(row.documentId()), row.title(),
                        Long.toString(row.publicationCount()), row.latestPublishedAt()));
        return new KnowledgeViews.DigestDetail(digest(digest), groups);
    }

    @Override
    public SubscriptionViews.Page<KnowledgeViews.Update> members(long actor, UUID digestId, long documentId, int limit, String cursor) {
        SubscriptionRules.limit(limit);
        var scope = access.current(actor);
        requireDigest(scope, digestId);
        String context = SubscriptionCursor.context(actor, "knowledge-digest-members", digestId.toString(), documentId);
        String position = SubscriptionCursor.decode(cursor, context);
        var query = query(scope, null, digestId, documentId, null,
                position == null ? null : SubscriptionRules.number(position, 1), null, null, limit + 1);
        return page(mapper.members(query), limit, context, row -> Long.toString(row.position()), this::update);
    }

    private KnowledgeRows.Digest requireDigest(PermissionScope scope, UUID id) {
        var row = mapper.digest(query(scope, null, id, null, null, null, null, null, 1));
        if (row == null) {
            throw SubscriptionFailure.hidden();
        }
        return row;
    }

    private KnowledgeQuery query(PermissionScope scope, UUID update, UUID digest, Long document, LocalDate week,
                                 Long before, Long beforeDocument, LocalDate beforeWeek, int limit) {
        return new KnowledgeQuery(scope.userId(), scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), update, digest, document, week, null, before, beforeDocument, beforeWeek, limit);
    }

    private KnowledgeViews.Update update(KnowledgeRows.Update row) {
        return new KnowledgeViews.Update(row.updateId(), Long.toString(row.documentId()), Long.toString(row.versionId()),
                row.versionNo(), row.title(), row.publishedAt(), row.weekStart(), row.mode());
    }

    private KnowledgeViews.Digest digest(KnowledgeRows.Digest row) {
        return new KnowledgeViews.Digest(row.digestId(), row.weekStart(), row.weekStart().plusWeeks(1), row.generatedAt());
    }

    private <T, V> SubscriptionViews.Page<V> page(List<T> rows, int limit, String context, Function<T, String> position, Function<T, V> view) {
        if (rows == null || rows.size() > limit + 1) {
            throw SubscriptionFailure.unavailable();
        }
        boolean more = rows.size() > limit;
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        return new SubscriptionViews.Page<>(selected.stream().map(view).toList(), more,
                more ? SubscriptionCursor.encode(context, position.apply(selected.getLast())) : null, Instant.now());
    }
}
