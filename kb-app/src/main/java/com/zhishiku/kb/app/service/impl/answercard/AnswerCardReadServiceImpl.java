package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.answercard.AnswerCardRevision;
import com.zhishiku.kb.app.domain.answercard.AnswerCardReview;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardPageInput;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardQuery;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardViews;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardQueryMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardSourceMapper;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.service.answercard.AnswerCardReadService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/** 每次读取使用同一数据库快照、业务日期和当前主体；不保留正文或资格缓存。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
public class AnswerCardReadServiceImpl implements AnswerCardReadService {
    private final AnswerCardMapper mapper;
    private final AnswerCardQueryMapper queries;
    private final AnswerCardSourceMapper sources;
    private final AnswerCardAccess access;
    private final ObjectMapper json;

    public AnswerCardReadServiceImpl(AnswerCardMapper mapper, AnswerCardQueryMapper queries,
                                     AnswerCardSourceMapper sources, AnswerCardAccess access, ObjectMapper json) {
        this.mapper = mapper;
        this.queries = queries;
        this.sources = sources;
        this.access = access;
        this.json = json;
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.Item> browse(long actor, AnswerCardPageInput input) {
        var handle = access.current(actor);
        LocalDate today = date(mapper.now());
        var filters = filters(actor, null, input);
        var rows = queries.browse(query(actor, null, input, "browse", filters, today));
        return page(rows, input.limit(), "browse", filters, today, Long::longValue, id -> item(handle, card(id), today));
    }

    @Override
    public AnswerCardViews.Search search(long actor, String query, Long spaceId, int limit) {
        var handle = access.current(actor);
        String text = AnswerCardContent.text(query, 1, 256);
        if (limit < 1 || limit > 20 || spaceId != null && spaceId <= 0) {
            throw AnswerCardFailure.invalid();
        }
        OffsetDateTime now = mapper.now();
        LocalDate today = date(now);
        var rows = queries.search(new AnswerCardQuery(actor, spaceId, null, null, null, null, limit, today,
                text, AnswerCardContent.normalize(text)));
        if (rows == null || rows.size() > limit) {
            throw AnswerCardFailure.unavailable();
        }
        var items = new ArrayList<AnswerCardViews.Item>();
        for (long id : new LinkedHashSet<>(rows)) {
            items.add(item(handle, card(id), today));
        }
        return new AnswerCardViews.Search(List.copyOf(items), today, now, text, AnswerCardReceipt.id(spaceId));
    }

    @Override
    public AnswerCardViews.Detail detail(long actor, long cardId, long revisionId) {
        var handle = access.current(actor);
        LocalDate today = date(mapper.now());
        var input = new AnswerCardQuery(actor, null, cardId, revisionId, null, null, 1, today, null, null);
        if (!queries.displayable(input)) {
            throw AnswerCardFailure.hidden();
        }
        var card = card(cardId);
        if (!Objects.equals(card.publishedRevisionId(), revisionId)) {
            throw AnswerCardFailure.hidden();
        }
        return new AnswerCardViews.Detail(revision(handle, card, revisionId, today, false), stewardship(card, today, false), today);
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.Workspace> workspaces(long actor, AnswerCardPageInput input) {
        var handle = access.current(actor);
        if (!Set.of("editable", "reviewable", "mine", "attention").contains(input.view())) {
            throw AnswerCardFailure.invalid();
        }
        access.requireFunction(handle, "reviewable".equals(input.view()) ? Permission.ANSWER_CARD_REVIEW : Permission.ANSWER_CARD_WRITE);
        LocalDate today = date(mapper.now());
        var filters = filters(actor, null, input);
        var rows = queries.workspace(query(actor, null, input, "workspace", filters, today));
        return page(rows, input.limit(), "workspace", filters, today, Long::longValue, id -> workspace(handle, card(id), today));
    }

    @Override
    public AnswerCardViews.Workspace workspace(long actor, long cardId) {
        var handle = access.current(actor);
        requireContentFunction(handle);
        var card = card(cardId);
        access.requireContentOperator(handle, card.spaceId());
        return workspace(handle, card, date(mapper.now()));
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.History> history(long actor, long cardId, AnswerCardPageInput input) {
        var handle = access.current(actor);
        requireContentFunction(handle);
        var card = card(cardId);
        access.requireContentOperator(handle, card.spaceId());
        if (!Set.of("revisions", "reviews").contains(input.view())) {
            throw AnswerCardFailure.invalid();
        }
        LocalDate today = date(mapper.now());
        var filters = filters(actor, cardId, input);
        var query = query(actor, cardId, input, "history", filters, today);
        boolean reviews = "reviews".equals(input.view());
        var rows = reviews ? queries.reviewHistory(query) : queries.history(query);
        return page(rows, input.limit(), "history", filters, today, Long::longValue, id -> {
            if (reviews) {
                var round = mapper.review(id);
                access.requireSources(handle, AnswerCardWriteServiceImpl.keys(mapper.sources(round.revisionId())), false);
                return new AnswerCardViews.History(null, review(round));
            }
            return new AnswerCardViews.History(revision(handle, card, id, today, true), review(mapper.latestReview(id)));
        });
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.Management> management(long actor, AnswerCardPageInput input) {
        var handle = access.current(actor);
        if (input.spaceId() == null) {
            throw AnswerCardFailure.invalid();
        }
        var space = access.requireManager(handle, input.spaceId(), false);
        LocalDate today = date(mapper.now());
        var filters = filters(actor, null, input);
        var rows = queries.management(query(actor, null, input, "management", filters, today));
        return page(rows, input.limit(), "management", filters, today, Long::longValue,
                id -> management(card(id), Boolean.TRUE.equals(space.getArchived()), today));
    }

    @Override
    public AnswerCardViews.Management management(long actor, long cardId) {
        var handle = access.current(actor);
        var card = card(cardId);
        var space = access.requireManager(handle, card.spaceId(), false);
        return management(card, Boolean.TRUE.equals(space.getArchived()), date(mapper.now()));
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.Person> candidates(long actor, long cardId, AnswerCardPageInput input) {
        var handle = access.current(actor);
        var card = card(cardId);
        access.requireManager(handle, card.spaceId(), true);
        String name = AnswerCardContent.text(input.name(), 0, 64);
        LocalDate today = date(mapper.now());
        var filters = filters(actor, cardId, input);
        var rows = queries.candidates(cardId, workingRevision(card), QueryParameters.like(name),
                after(input.cursor(), "candidates", filters), input.limit() + 1);
        return page(rows, input.limit(), "candidates", filters, today, value -> value.userId(),
                value -> new AnswerCardViews.Person(Long.toString(value.userId()), value.displayName()));
    }

    @Override
    public AnswerCardViews.Page<AnswerCardViews.SourceOption> sources(long actor, AnswerCardPageInput input) {
        var handle = access.current(actor);
        if (input.spaceId() == null) {
            throw AnswerCardFailure.invalid();
        }
        access.requireWriter(handle, input.spaceId());
        String name = AnswerCardContent.text(input.name(), 0, 128);
        var scope = access.view(handle);
        LocalDate today = date(mapper.now());
        var filters = filters(actor, null, input);
        var rows = sources.candidates(QueryParameters.like(name), after(input.cursor(), "sources", filters), input.limit() + 1,
                scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel());
        return page(rows, input.limit(), "sources", filters, today, value -> value.documentId(), value -> {
            if (!Objects.equals(value.currentVersionId(), value.versionId())) {
                throw AnswerCardFailure.unavailable();
            }
            return new AnswerCardViews.SourceOption(Long.toString(value.documentId()), Long.toString(value.versionId()),
                    value.title(), Integer.toString(value.versionNo()), Long.toString(value.applicabilityVersion()),
                    dates(new AnswerCardDates(value.effectiveFrom(), value.untilKind(), value.effectiveUntil()), today));
        });
    }

    private AnswerCardViews.Item item(PermissionService.PrincipalHandle handle, AnswerCard card, LocalDate today) {
        var revision = revision(handle, card, card.publishedRevisionId(), today, false);
        if (!"AVAILABLE".equals(revision.availability())) {
            throw AnswerCardFailure.hidden();
        }
        String answer = revision.answer();
        boolean truncated = answer.codePointCount(0, answer.length()) > 400;
        return new AnswerCardViews.Item(revision.cardId(), revision.revisionId(), revision.revisionNo(), revision.question(),
                truncated ? answer.substring(0, answer.offsetByCodePoints(0, 400)) : answer, truncated,
                revision.applicabilityText(), revision.dates(), stewardship(card, today, false).state(),
                "/answer-cards/" + card.id() + "/revisions/" + revision.revisionId());
    }

    private AnswerCardViews.Revision revision(PermissionService.PrincipalHandle handle, AnswerCard card,
                                               Long id, LocalDate today, boolean privateView) {
        if (id == null || !access.space(handle, card.spaceId(), PermissionService.PERM_VIEW)) {
            throw AnswerCardFailure.hidden();
        }
        var bindings = mapper.sources(id);
        var facts = access.requireSources(handle, AnswerCardWriteServiceImpl.keys(bindings), false);
        AnswerCardRevision revision = mapper.revision(id);
        if (revision == null || revision.cardId() != card.id()) {
            throw AnswerCardFailure.hidden();
        }
        var resultSources = new ArrayList<AnswerCardViews.Source>();
        for (var bound : bindings) {
            var fact = facts.stream().filter(value -> value.documentId() == bound.documentId()
                    && value.versionId() == bound.versionId()).findFirst().orElseThrow(AnswerCardFailure::hidden);
            resultSources.add(new AnswerCardViews.Source(Long.toString(bound.documentId()), Long.toString(bound.versionId()),
                    Integer.toString(fact.versionNo()), fact.title(), Long.toString(bound.applicabilityVersion()),
                    dates(new AnswerCardDates(bound.effectiveFrom(), bound.untilKind(), bound.effectiveUntil()), today),
                    dates(new AnswerCardDates(fact.effectiveFrom(), fact.untilKind(), fact.effectiveUntil()), today),
                    Long.toString(fact.applicabilityVersion()), AnswerCardReceipt.id(fact.currentVersionId()),
                    "/api/documents/" + bound.documentId() + "/versions/" + bound.versionId() + "/preview"));
        }
        var declaration = AnswerCardWriteServiceImpl.dates(revision);
        String availability = AnswerCardRules.availability(declaration, bindings, facts, today);
        if (!privateView && (!Objects.equals(card.publishedRevisionId(), id) || !"AVAILABLE".equals(availability))) {
            throw AnswerCardFailure.hidden();
        }
        if (!Objects.equals(card.publishedRevisionId(), id) && "AVAILABLE".equals(availability)) {
            var lastReview = mapper.latestReview(id);
            availability = lastReview != null && lastReview.state() == com.zhishiku.kb.app.enums.answercard.AnswerCardReviewState.APPROVED
                    ? "HISTORICAL" : "DRAFT";
        }
        List<String> aliases;
        try {
            aliases = json.readValue(revision.aliasesJson(), new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            throw AnswerCardFailure.unavailable();
        }
        return new AnswerCardViews.Revision(Long.toString(card.id()), Long.toString(id), Long.toString(revision.revisionNo()),
                privateView ? Long.toString(revision.branchId()) : null, revision.question(), List.copyOf(aliases), revision.answer(),
                revision.applicabilityText(), dates(declaration, today), List.copyOf(resultSources), availability);
    }

    private AnswerCardViews.Workspace workspace(PermissionService.PrincipalHandle handle, AnswerCard card, LocalDate today) {
        access.requireContentOperator(handle, card.spaceId());
        var published = optionalRevision(handle, card, card.publishedRevisionId(), today);
        var branch = card.activeBranchId() == null ? null : mapper.branch(card.activeBranchId());
        var draft = optionalRevision(handle, card, branch == null ? null : branch.latestRevisionId(), today);
        if (published == null && draft == null) {
            throw AnswerCardFailure.hidden();
        }
        var round = draft == null ? null : mapper.activeReview(card.id());
        boolean writer = handle.functionalPermissions().contains(Permission.ANSWER_CARD_WRITE.code())
                && access.space(handle, card.spaceId(), PermissionService.PERM_EDIT);
        boolean reviewer = handle.functionalPermissions().contains(Permission.ANSWER_CARD_REVIEW.code())
                && access.space(handle, card.spaceId(), PermissionService.PERM_REVIEW);
        boolean self = round != null && (round.submittedBy() == handle.userId()
                || mapper.contributors(round.branchId()).contains(handle.userId()));
        var stewardship = stewardship(card, today, true);
        var setting = mapper.stewardship(card.id());
        var capabilities = new AnswerCardViews.Capabilities(writer && round == null && (branch == null || draft != null),
                writer && draft != null && round == null, reviewer && round != null && !self,
                writer && round != null && round.submittedBy() == handle.userId(),
                writer && setting != null && setting.responsibleUserId() == handle.userId() && branch == null
                        && published != null && "AVAILABLE".equals(published.availability())
                        && !"NEEDS_REASSIGN".equals(stewardship.state()));
        return new AnswerCardViews.Workspace(Long.toString(card.id()), Long.toString(card.spaceId()), Long.toString(card.cardVersion()),
                published, draft, review(round), stewardship, capabilities, today);
    }

    private AnswerCardViews.Revision optionalRevision(PermissionService.PrincipalHandle actor, AnswerCard card, Long id, LocalDate today) {
        if (id == null) {
            return null;
        }
        try {
            return revision(actor, card, id, today, true);
        } catch (AnswerCardFailure failure) {
            if (failure.status() == 404) {
                return null;
            }
            throw failure;
        }
    }

    private AnswerCardViews.Management management(AnswerCard card, boolean archived, LocalDate today) {
        String state = mapper.hasActiveReview(card.id()) ? "IN_REVIEW" : card.publishedRevisionId() == null ? "DRAFT" : "PUBLISHED";
        return new AnswerCardViews.Management(Long.toString(card.id()), Long.toString(card.spaceId()), Long.toString(card.cardVersion()),
                state, archived, stewardship(card, today, true), today);
    }

    private AnswerCardViews.Stewardship stewardship(AnswerCard card, LocalDate today, boolean identity) {
        var setting = mapper.stewardship(card.id());
        boolean eligible = setting != null && queries.eligible(card.id(), workingRevision(card), setting.responsibleUserId(), false);
        String state = AnswerCardRules.stewardship(setting, eligible, today);
        if (setting == null) {
            return new AnswerCardViews.Stewardship(state, null, null, null, null, null, "0");
        }
        var person = identity ? queries.responsible(card.id()) : null;
        return new AnswerCardViews.Stewardship(state, person == null ? null
                : new AnswerCardViews.Person(Long.toString(person.userId()), person.displayName()), setting.cycleMonths(),
                setting.firstAssignedOn(), setting.lastConfirmedOn(), AnswerCardRules.dueDate(setting), Long.toString(setting.stewardshipVersion()));
    }

    private long workingRevision(AnswerCard card) {
        if (card.publishedRevisionId() != null) {
            return card.publishedRevisionId();
        }
        var branch = card.activeBranchId() == null ? null : mapper.branch(card.activeBranchId());
        if (branch == null || branch.latestRevisionId() == null) {
            throw AnswerCardFailure.unavailable();
        }
        return branch.latestRevisionId();
    }

    private AnswerCard card(long id) {
        var card = mapper.card(id);
        if (card == null) {
            throw AnswerCardFailure.hidden();
        }
        return card;
    }

    private static void requireContentFunction(PermissionService.PrincipalHandle actor) {
        if (!actor.functionalPermissions().contains(Permission.ANSWER_CARD_WRITE.code())
                && !actor.functionalPermissions().contains(Permission.ANSWER_CARD_REVIEW.code())) {
            throw AnswerCardFailure.forbidden();
        }
    }

    private static AnswerCardViews.Review review(AnswerCardReview review) {
        return review == null ? null : new AnswerCardViews.Review(Long.toString(review.id()), Long.toString(review.revisionId()),
                Long.toString(review.roundNo()), review.state().name(), Long.toString(review.reviewVersion()),
                Long.toString(review.submittedBy()), review.submittedAt(), review.decidedAt(), review.reason());
    }

    public static LocalDate date(OffsetDateTime value) {
        if (value == null) {
            throw AnswerCardFailure.unavailable();
        }
        return value.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
    }

    private static AnswerCardViews.Dates dates(AnswerCardDates value, LocalDate today) {
        return new AnswerCardViews.Dates(value.effectiveFrom(), value.untilKind(), value.effectiveUntil(), value.state(today));
    }

    private static Map<String, Object> filters(long actor, Long card, AnswerCardPageInput input) {
        return QueryParameters.filters("actor", actor, "card", card, "space", input.spaceId(), "view", input.view(),
                "revision", input.revisionId(), "name", input.name(), "limit", input.limit());
    }

    private static Long after(String token, String kind, Map<String, Object> filters) {
        var cursor = QueryCursor.decode(token, "answer-card-" + kind);
        if (cursor == null) {
            return null;
        }
        cursor.verify("answer-card-" + kind, filters);
        return cursor.id();
    }

    private static AnswerCardQuery query(long actor, Long card, AnswerCardPageInput input, String kind,
                                          Map<String, Object> filters, LocalDate today) {
        return new AnswerCardQuery(actor, input.spaceId(), card, input.revisionId(), input.view(), after(input.cursor(), kind, filters),
                input.limit() + 1, today, null, null);
    }

    private static <T, R> AnswerCardViews.Page<R> page(List<T> rows, int limit, String kind, Map<String, Object> filters,
                                                      LocalDate today, ToLongFunction<T> identity, Function<T, R> project) {
        if (rows == null || rows.size() > limit + 1) {
            throw AnswerCardFailure.unavailable();
        }
        boolean more = rows.size() > limit;
        var slice = rows.subList(0, Math.min(rows.size(), limit));
        String cursor = more ? QueryCursor.encode("answer-card-" + kind, filters, identity.applyAsLong(slice.getLast())) : null;
        return new AnswerCardViews.Page<>(slice.stream().map(project).toList(), more, cursor, today);
    }
}
