package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.answercard.AnswerCardBranch;
import com.zhishiku.kb.app.domain.answercard.AnswerCardConfirmation;
import com.zhishiku.kb.app.domain.answercard.AnswerCardRevision;
import com.zhishiku.kb.app.domain.answercard.AnswerCardReview;
import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardStewardship;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardBinding;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardIntent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceLocation;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.enums.answercard.AnswerCardReviewState;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardQueryMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.service.answercard.AnswerCardWriteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 有界短事务执行卡片命令；不调用文件、模型或其他服务。 */
@Service
public class AnswerCardWriteServiceImpl implements AnswerCardWriteService {
    private final AnswerCardMapper mapper;
    private final AnswerCardQueryMapper queries;
    private final AnswerCardAccess access;
    private final AnswerCardCommands commands;
    private final IdentityAuditService audit;

    public AnswerCardWriteServiceImpl(AnswerCardMapper mapper, AnswerCardQueryMapper queries, AnswerCardAccess access,
                                      AnswerCardCommands commands, IdentityAuditService audit) {
        this.mapper = mapper;
        this.queries = queries;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
    public AnswerCardReceipt execute(long actor, String requestId, AnswerCardIntent input) {
        validateIntent(input);
        authorize(access.current(actor), input, false);
        mapper.limits();
        AnswerCardBinding binding = binding(actor, requestId, input);
        var previous = commands.lock(binding);
        if (previous != null) {
            authorize(access.current(actor), input, true);
            return commands.receipt(previous, AnswerCardReceipt.class).replay();
        }
        var before = input.cardId() == null ? null : requireCard(input.cardId());
        lockResources(input, before);
        var current = before == null ? null : mapper.lockCard(before.id());
        if (before != null && (current == null || current.cardVersion() != before.cardVersion()
                || !Objects.equals(current.activeBranchId(), before.activeBranchId())
                || !Objects.equals(current.publishedRevisionId(), before.publishedRevisionId()))) {
            throw AnswerCardFailure.conflict("CARD_VERSION_CONFLICT");
        }
        if (current != null) {
            if (current.activeBranchId() != null) {
                mapper.lockBranch(current.activeBranchId());
            }
            mapper.lockReview(current.id());
            mapper.lockStewardship(current.id());
        }
        var handle = access.current(actor);
        authorize(handle, input, false);
        if (current != null && !Objects.equals(input.expectedCardVersion(), current.cardVersion())) {
            throw AnswerCardFailure.conflict("CARD_VERSION_CONFLICT");
        }
        OffsetDateTime now = Objects.requireNonNull(mapper.now());
        LocalDate today = now.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var changedFields = changedFields(input);
        var priorSetting = current == null ? null : mapper.stewardship(current.id());
        var receipt = switch (input.operation()) {
            case ANSWER_CARD_CREATE, ANSWER_CARD_SAVE -> save(handle, input, current, now);
            case ANSWER_CARD_SUBMIT -> submit(handle, input, current, now, today);
            case ANSWER_CARD_APPROVE, ANSWER_CARD_REJECT, ANSWER_CARD_WITHDRAW -> decide(handle, input, current, now, today);
            case ANSWER_CARD_STEWARDSHIP_UPDATE -> assign(input, current, now, today);
            case ANSWER_CARD_CONFIRM -> confirm(handle, input, current, now, today);
        };
        var summary = new LinkedHashMap<String, Object>();
        summary.put("cardId", Long.parseLong(receipt.cardId()));
        summary.put("spaceId", input.spaceId());
        summary.put("cardVersion", Long.parseLong(receipt.cardVersion()));
        summary.put("revisionId", parse(receipt.revisionId()));
        summary.put("branchId", parse(receipt.branchId()));
        summary.put("reviewId", parse(receipt.reviewId()));
        summary.put("state", receipt.state());
        summary.put("reasonProvided", input.reason() != null);
        summary.put("responsibleUserId", input.responsibleUserId());
        summary.put("cycleMonths", input.cycleMonths());
        if (input.content() != null) {
            summary.put("changedFields", changedFields);
            summary.put("effectiveFrom", input.content().dates().effectiveFrom());
            summary.put("untilKind", input.content().dates().untilKind());
            summary.put("effectiveUntil", input.content().dates().effectiveUntil());
        }
        if (input.operation() == AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE
                || input.operation() == AnswerCardOperation.ANSWER_CARD_CONFIRM) {
            var setting = mapper.stewardship(Long.parseLong(receipt.cardId()));
            summary.put("lastConfirmedOn", setting.lastConfirmedOn());
            summary.put("dueDate", AnswerCardRules.dueDate(setting));
            summary.put("stewardshipVersion", setting.stewardshipVersion());
            if (input.operation() == AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE) {
                var changed = new ArrayList<String>();
                if (priorSetting == null || priorSetting.responsibleUserId() != setting.responsibleUserId()) {
                    changed.add("responsible");
                }
                if (priorSetting == null || priorSetting.cycleMonths() != setting.cycleMonths()) {
                    changed.add("cycleMonths");
                }
                summary.put("changedFields", changed);
            }
        }
        var beforeSummary = new LinkedHashMap<String, Object>();
        if (current != null) {
            beforeSummary.put("cardId", current.id());
            beforeSummary.put("cardVersion", current.cardVersion());
            if (priorSetting != null && receipt.stewardshipVersion() != null) {
                beforeSummary.put("stewardshipVersion", priorSetting.stewardshipVersion());
                beforeSummary.put("responsibleUserId", priorSetting.responsibleUserId());
                beforeSummary.put("cycleMonths", priorSetting.cycleMonths());
                beforeSummary.put("lastConfirmedOn", priorSetting.lastConfirmedOn());
                beforeSummary.put("dueDate", AnswerCardRules.dueDate(priorSetting));
            }
        }
        audit.appendSuccess(AuditScope.valueOf(input.operation().name()), binding.requestId(), actor,
                Long.parseLong(receipt.cardId()), input.operation().name(), commands.write(beforeSummary), commands.write(summary));
        commands.save(binding, receipt);
        return receipt;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public AnswerCardReceipt query(long actor, String requestId, AnswerCardIntent intent) {
        validateIntent(intent);
        authorize(access.current(actor), intent, true);
        var result = commands.find(binding(actor, requestId, intent));
        return result == null ? null : commands.receipt(result, AnswerCardReceipt.class).replay();
    }

    private AnswerCardReceipt save(PermissionService.PrincipalHandle actor, AnswerCardIntent input,
                                   AnswerCard card, OffsetDateTime now) {
        if (card != null && mapper.activeReview(card.id()) != null) {
            throw AnswerCardFailure.conflict("REVIEW_IN_PROGRESS");
        }
        var content = input.content();
        var facts = access.requireSources(actor, content.sources(), true);
        boolean creating = card == null;
        long cardId = creating ? mapper.nextCardId() : card.id();
        AnswerCardBranch branch;
        if (creating || card.activeBranchId() == null) {
            if (!creating && (input.branchId() != null || !Objects.equals(input.revisionId(), card.publishedRevisionId()))) {
                throw AnswerCardFailure.conflict("DRAFT_CHANGED");
            }
            long branchId = mapper.nextBranchId();
            branch = new AnswerCardBranch(branchId, cardId, creating ? 1 : mapper.nextBranchNo(cardId), null, false);
            if (creating) {
                one(mapper.insertCard(new AnswerCard(cardId, input.spaceId(), 0, null, branchId, now)));
            }
            one(mapper.insertBranch(branch));
        } else {
            branch = mapper.branch(card.activeBranchId());
            if (branch == null || !Objects.equals(input.branchId(), branch.id()) || branch.closed()
                    || !Objects.equals(input.revisionId(), branch.latestRevisionId())) {
                throw AnswerCardFailure.conflict("DRAFT_CHANGED");
            }
        }
        long revisionId = mapper.nextRevisionId();
        var dates = content.dates();
        var revision = new AnswerCardRevision(revisionId, cardId, branch.id(), creating ? 1 : mapper.nextRevisionNo(cardId),
                content.question(), commands.write(content.aliases()), content.answer(), content.applicabilityText(),
                dates.effectiveFrom(), dates.untilKind(), dates.effectiveUntil(), commands.write(content.normalizedQuestions()),
                content.questionText(), actor.userId(), now);
        one(mapper.insertRevision(revision));
        for (var source : facts) {
            one(mapper.insertSource(new AnswerCardSource(revisionId, source.documentId(), source.versionId(),
                    source.applicabilityVersion(), source.effectiveFrom(), source.untilKind(), source.effectiveUntil())));
        }
        mapper.contribute(branch.id(), actor.userId());
        one(mapper.advanceBranch(branch.id(), revisionId));
        if (!creating) {
            advance(card, card.publishedRevisionId(), branch.id());
        }
        return receipt(cardId, creating ? 0 : card.cardVersion() + 1, branch.id(), revisionId, null, null,
                null, "DRAFT", now);
    }

    private AnswerCardReceipt submit(PermissionService.PrincipalHandle actor, AnswerCardIntent input,
                                     AnswerCard card, OffsetDateTime now, LocalDate today) {
        var revision = requireDraft(card, input);
        if (mapper.activeReview(card.id()) != null) {
            throw AnswerCardFailure.conflict("REVIEW_IN_PROGRESS");
        }
        ready(actor, card, revision, today);
        if (!queries.hasReviewer(card.id(), revision.id(), revision.branchId(), actor.userId())) {
            throw AnswerCardFailure.conflict("NO_REVIEWER");
        }
        long id = mapper.nextReviewId();
        var review = new AnswerCardReview(id, card.id(), revision.branchId(), revision.id(), mapper.nextRoundNo(card.id()),
                AnswerCardReviewState.IN_REVIEW, 0, actor.userId(), now, null, null, null);
        one(mapper.insertReview(review));
        advance(card, card.publishedRevisionId(), card.activeBranchId());
        return receipt(card.id(), card.cardVersion() + 1, revision.branchId(), revision.id(), id, 0L, null, "IN_REVIEW", now);
    }

    private AnswerCardReceipt decide(PermissionService.PrincipalHandle actor, AnswerCardIntent input,
                                     AnswerCard card, OffsetDateTime now, LocalDate today) {
        var revision = requireDraft(card, input);
        var review = requireReview(input, card);
        if (review.state() != AnswerCardReviewState.IN_REVIEW || !Objects.equals(input.expectedReviewVersion(), review.reviewVersion())
                || review.revisionId() != revision.id()) {
            throw AnswerCardFailure.conflict("REVIEW_CHANGED");
        }
        String state;
        Long published = card.publishedRevisionId();
        Long branch = card.activeBranchId();
        switch (input.operation()) {
            case ANSWER_CARD_APPROVE -> {
                ready(actor, card, revision, today);
                state = "APPROVED";
                published = revision.id();
                branch = null;
                one(mapper.closeBranch(revision.branchId()));
            }
            case ANSWER_CARD_REJECT -> state = "REJECTED";
            case ANSWER_CARD_WITHDRAW -> state = "WITHDRAWN";
            default -> throw AnswerCardFailure.invalid();
        }
        one(mapper.decide(review.id(), review.reviewVersion(), state, actor.userId(), now, input.reason()));
        advance(card, published, branch);
        return receipt(card.id(), card.cardVersion() + 1, revision.branchId(), revision.id(), review.id(),
                review.reviewVersion() + 1, null, state, now);
    }

    private AnswerCardReceipt assign(AnswerCardIntent input, AnswerCard card, OffsetDateTime now, LocalDate today) {
        long revision = workingRevision(card);
        var old = mapper.stewardship(card.id());
        long version = old == null ? 0 : old.stewardshipVersion();
        if (!Objects.equals(input.expectedStewardshipVersion(), version)) {
            throw AnswerCardFailure.conflict("STEWARDSHIP_VERSION_CONFLICT");
        }
        if (!queries.eligible(card.id(), revision, input.responsibleUserId(), false)) {
            throw AnswerCardFailure.conflict("STEWARD_INELIGIBLE");
        }
        if (old != null && old.responsibleUserId() == input.responsibleUserId() && old.cycleMonths() == input.cycleMonths()) {
            throw AnswerCardFailure.conflict("NO_CHANGE");
        }
        var next = new AnswerCardStewardship(card.id(), input.responsibleUserId(), input.cycleMonths(),
                old == null ? today : old.firstAssignedOn(), old == null ? null : old.lastConfirmedOn(),
                old == null ? null : old.lastConfirmedRevisionId(), Math.incrementExact(version));
        one(old == null ? mapper.insertStewardship(next) : mapper.updateStewardship(next));
        advance(card, card.publishedRevisionId(), card.activeBranchId());
        return receipt(card.id(), card.cardVersion() + 1, null, null, null, null, next.stewardshipVersion(), "ASSIGNED", now);
    }

    private AnswerCardReceipt confirm(PermissionService.PrincipalHandle actor, AnswerCardIntent input,
                                      AnswerCard card, OffsetDateTime now, LocalDate today) {
        var setting = mapper.stewardship(card.id());
        if (setting == null || setting.responsibleUserId() != actor.userId()) {
            throw AnswerCardFailure.forbidden();
        }
        if (card.activeBranchId() != null || card.publishedRevisionId() == null
                || !Objects.equals(card.publishedRevisionId(), input.revisionId())
                || !Objects.equals(input.expectedStewardshipVersion(), setting.stewardshipVersion())) {
            throw AnswerCardFailure.conflict("CONFIRMATION_CONFLICT");
        }
        var revision = mapper.revision(card.publishedRevisionId());
        var bindings = mapper.sources(revision.id());
        var facts = access.requireSources(actor, keys(bindings), false);
        if (!"AVAILABLE".equals(AnswerCardRules.availability(dates(revision), bindings, facts, today))) {
            throw AnswerCardFailure.conflict("CARD_NOT_AVAILABLE");
        }
        var next = new AnswerCardStewardship(card.id(), actor.userId(), setting.cycleMonths(), setting.firstAssignedOn(),
                today, revision.id(), Math.incrementExact(setting.stewardshipVersion()));
        one(mapper.updateStewardship(next));
        one(mapper.insertConfirmation(new AnswerCardConfirmation(0, card.id(), revision.id(), actor.userId(), today,
                setting.stewardshipVersion(), next.stewardshipVersion())));
        advance(card, card.publishedRevisionId(), null);
        return receipt(card.id(), card.cardVersion() + 1, null, revision.id(), null, null, next.stewardshipVersion(), "CONFIRMED", now);
    }

    private void ready(PermissionService.PrincipalHandle actor, AnswerCard card, AnswerCardRevision revision, LocalDate today) {
        var bound = mapper.sources(revision.id());
        if (revision.answer().isBlank() || revision.applicabilityText().isBlank() || bound.isEmpty()) {
            throw AnswerCardFailure.conflict("INCOMPLETE_DRAFT");
        }
        List<AnswerCardSourceFact> facts = access.requireSources(actor, keys(bound), true);
        AnswerCardRules.requireFutureIntersection(dates(revision), facts, today);
        var setting = mapper.stewardship(card.id());
        if (setting == null || !queries.eligible(card.id(), revision.id(), setting.responsibleUserId(), false)
                || !queries.eligible(card.id(), workingRevision(card), setting.responsibleUserId(), false)) {
            throw AnswerCardFailure.conflict("STEWARD_INELIGIBLE");
        }
    }

    private void authorize(PermissionService.PrincipalHandle actor, AnswerCardIntent input, boolean replay) {
        boolean management = input.operation() == AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE;
        boolean reviewing = input.operation() == AnswerCardOperation.ANSWER_CARD_APPROVE
                || input.operation() == AnswerCardOperation.ANSWER_CARD_REJECT;
        if (management) {
            access.requireManager(actor, input.spaceId(), true);
        } else if (reviewing) {
            access.requireReviewer(actor, input.spaceId());
        } else {
            access.requireWriter(actor, input.spaceId());
        }
        if (input.cardId() == null) {
            access.requireSources(actor, input.content().sources(), false);
            return;
        }
        var card = requireCard(input.cardId());
        if (card.spaceId() != input.spaceId()) {
            throw AnswerCardFailure.hidden();
        }
        if (management) {
            return;
        }
        if (input.revisionId() != null) {
            var revision = mapper.revision(input.revisionId());
            if (revision == null || revision.cardId() != card.id()
                    || input.branchId() != null && revision.branchId() != input.branchId()) {
                throw AnswerCardFailure.hidden();
            }
            access.requireSources(actor, keys(mapper.sources(revision.id())), false);
        } else if (!replay && input.operation() == AnswerCardOperation.ANSWER_CARD_SAVE) {
            access.requireSources(actor, keys(mapper.sources(workingRevision(card))), false);
        }
        if (input.content() != null) {
            access.requireSources(actor, input.content().sources(), false);
        }
        if (reviewing || input.operation() == AnswerCardOperation.ANSWER_CARD_WITHDRAW) {
            var review = requireReview(input, card);
            if (reviewing && (review.submittedBy() == actor.userId()
                    || mapper.contributors(review.branchId()).contains(actor.userId()))) {
                throw AnswerCardFailure.forbidden();
            }
            if (!reviewing && review.submittedBy() != actor.userId()) {
                throw AnswerCardFailure.forbidden();
            }
        }
        if (input.operation() == AnswerCardOperation.ANSWER_CARD_CONFIRM) {
            var setting = mapper.stewardship(card.id());
            if (setting == null || setting.responsibleUserId() != actor.userId()) {
                throw AnswerCardFailure.forbidden();
            }
        }
    }

    private void lockResources(AnswerCardIntent input, AnswerCard card) {
        var all = new ArrayList<AnswerCardSourceKey>();
        if (input.content() != null) {
            all.addAll(input.content().sources());
        }
        if (card != null) {
            if (card.publishedRevisionId() != null) {
                all.addAll(keys(mapper.sources(card.publishedRevisionId())));
            }
            if (card.activeBranchId() != null) {
                var branch = mapper.branch(card.activeBranchId());
                all.addAll(keys(mapper.sources(branch.latestRevisionId())));
            }
        }
        if (all.size() > 30) {
            throw AnswerCardFailure.unavailable();
        }
        var documents = all.stream().map(AnswerCardSourceKey::documentId).distinct().sorted().toList();
        var spaces = new TreeSet<Long>();
        spaces.add(input.spaceId());
        var locations = documents.isEmpty() ? List.<AnswerCardSourceLocation>of()
                : mapper.sourceLocations(documents);
        if (locations == null || locations.size() != documents.size()) {
            throw AnswerCardFailure.hidden();
        }
        locations.forEach(location -> spaces.add(location.spaceId()));
        for (long space : spaces) {
            if (mapper.shareTree(space) == null) {
                throw AnswerCardFailure.hidden();
            }
        }
        for (long document : documents) {
            if (mapper.shareDocument(document) == null) {
                throw AnswerCardFailure.hidden();
            }
        }
        for (long version : all.stream().map(AnswerCardSourceKey::versionId).distinct().sorted().toList()) {
            if (mapper.shareVersion(version) == null) {
                throw AnswerCardFailure.hidden();
            }
        }
        if (!documents.isEmpty() && !locations.equals(mapper.sourceLocations(documents))) {
            throw AnswerCardFailure.conflict("SOURCE_CHANGED");
        }
    }

    private AnswerCard requireCard(long id) {
        var card = mapper.card(id);
        if (card == null) {
            throw AnswerCardFailure.hidden();
        }
        return card;
    }

    private AnswerCardRevision requireDraft(AnswerCard card, AnswerCardIntent input) {
        if (card.activeBranchId() == null || !Objects.equals(card.activeBranchId(), input.branchId())) {
            throw AnswerCardFailure.conflict("DRAFT_CHANGED");
        }
        var branch = mapper.branch(card.activeBranchId());
        if (branch == null || branch.closed() || !Objects.equals(branch.latestRevisionId(), input.revisionId())) {
            throw AnswerCardFailure.conflict("DRAFT_CHANGED");
        }
        var revision = mapper.revision(branch.latestRevisionId());
        if (revision == null || revision.cardId() != card.id()) {
            throw AnswerCardFailure.unavailable();
        }
        return revision;
    }

    private AnswerCardReview requireReview(AnswerCardIntent input, AnswerCard card) {
        var review = mapper.review(input.reviewId());
        if (review == null || review.cardId() != card.id() || !Objects.equals(input.revisionId(), review.revisionId())
                || !Objects.equals(input.branchId(), review.branchId())) {
            throw AnswerCardFailure.hidden();
        }
        return review;
    }

    private long workingRevision(AnswerCard card) {
        if (card.publishedRevisionId() != null) {
            return card.publishedRevisionId();
        }
        if (card.activeBranchId() != null) {
            return mapper.branch(card.activeBranchId()).latestRevisionId();
        }
        throw AnswerCardFailure.conflict("NO_CONTENT");
    }

    private void advance(AnswerCard card, Long published, Long branch) {
        if (card.cardVersion() == Long.MAX_VALUE) {
            throw AnswerCardFailure.conflict("VERSION_EXHAUSTED");
        }
        one(mapper.advanceCard(card.id(), card.cardVersion(), published, branch));
    }

    private AnswerCardBinding binding(long actor, String requestId, AnswerCardIntent input) {
        return commands.bind(input.operation(), requestId, actor, input.spaceId(), input.cardId(), input.branchId(),
                input.revisionId(), input.reviewId(), input.expectedCardVersion(), input);
    }

    private List<String> changedFields(AnswerCardIntent input) {
        var content = input.content();
        if (content == null) {
            return List.of();
        }
        var fields = new ArrayList<String>();
        var old = input.revisionId() == null ? null : mapper.revision(input.revisionId());
        if (old == null || !old.question().equals(content.question())) {
            fields.add("question");
        }
        if (old == null || !commands.sameJson(old.aliasesJson(), content.aliases())) {
            fields.add("aliases");
        }
        if (old == null || !old.answer().equals(content.answer())) {
            fields.add("answer");
        }
        if (old == null || !old.applicabilityText().equals(content.applicabilityText())) {
            fields.add("applicabilityText");
        }
        if (old == null || !keys(mapper.sources(old.id())).equals(content.sources())) {
            fields.add("sources");
        }
        if (old == null || !Objects.equals(old.effectiveFrom(), content.dates().effectiveFrom())) {
            fields.add("effectiveFrom");
        }
        if (old == null || !old.untilKind().equals(content.dates().untilKind())) {
            fields.add("untilKind");
        }
        if (old == null || !Objects.equals(old.effectiveUntil(), content.dates().effectiveUntil())) {
            fields.add("effectiveUntil");
        }
        return List.copyOf(fields);
    }

    public static List<AnswerCardSourceKey> keys(List<AnswerCardSource> bindings) {
        return bindings.stream().map(value -> new AnswerCardSourceKey(value.documentId(), value.versionId(), value.applicabilityVersion())).toList();
    }

    public static AnswerCardDates dates(AnswerCardRevision revision) {
        return new AnswerCardDates(revision.effectiveFrom(), revision.untilKind(), revision.effectiveUntil());
    }

    private static Long parse(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static void one(int written) {
        if (written != 1) {
            throw AnswerCardFailure.conflict("STATE_CONFLICT");
        }
    }

    private static AnswerCardReceipt receipt(long card, long version, Long branch, Long revision, Long review,
                                              Long reviewVersion, Long stewardshipVersion, String state, OffsetDateTime now) {
        return new AnswerCardReceipt(Long.toString(card), Long.toString(version), AnswerCardReceipt.id(branch),
                AnswerCardReceipt.id(revision), AnswerCardReceipt.id(review), AnswerCardReceipt.id(reviewVersion),
                AnswerCardReceipt.id(stewardshipVersion), state, now, false);
    }

    private static void validateIntent(AnswerCardIntent value) {
        if (value == null || value.operation() == null || value.spaceId() <= 0) {
            throw AnswerCardFailure.invalid();
        }
        boolean create = value.operation() == AnswerCardOperation.ANSWER_CARD_CREATE;
        if (create != (value.cardId() == null) || create != (value.expectedCardVersion() == null)) {
            throw AnswerCardFailure.invalid();
        }
        for (Long id : java.util.Arrays.asList(value.cardId(), value.branchId(), value.revisionId(), value.reviewId(), value.responsibleUserId())) {
            if (id != null && id <= 0) {
                throw AnswerCardFailure.invalid();
            }
        }
        for (Long version : java.util.Arrays.asList(value.expectedCardVersion(), value.expectedReviewVersion(), value.expectedStewardshipVersion())) {
            if (version != null && version < 0) {
                throw AnswerCardFailure.invalid();
            }
        }
        boolean review = Set.of(AnswerCardOperation.ANSWER_CARD_APPROVE, AnswerCardOperation.ANSWER_CARD_REJECT,
                AnswerCardOperation.ANSWER_CARD_WITHDRAW).contains(value.operation());
        boolean branch = review || value.operation() == AnswerCardOperation.ANSWER_CARD_SUBMIT;
        boolean stewardship = value.operation() == AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE;
        boolean confirm = value.operation() == AnswerCardOperation.ANSWER_CARD_CONFIRM;
        if (review != (value.reviewId() != null) || review != (value.expectedReviewVersion() != null)
                || branch && value.branchId() == null || !create && !stewardship && value.revisionId() == null
                || (create || stewardship || confirm) && value.branchId() != null
                || (create || stewardship) && value.revisionId() != null
                || (stewardship || confirm) != (value.expectedStewardshipVersion() != null)
                || stewardship != (value.responsibleUserId() != null) || stewardship != (value.cycleMonths() != null)) {
            throw AnswerCardFailure.invalid();
        }
        if ((create || value.operation() == AnswerCardOperation.ANSWER_CARD_SAVE) != (value.content() != null)) {
            throw AnswerCardFailure.invalid();
        }
        if (value.operation() == AnswerCardOperation.ANSWER_CARD_REJECT) {
            AnswerCardContent.text(value.reason(), 1, 2000);
        } else if (value.reason() != null) {
            throw AnswerCardFailure.invalid();
        }
        if (value.operation() == AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE
                && (value.responsibleUserId() == null || value.responsibleUserId() <= 0 || value.cycleMonths() == null
                || !Set.of(3, 6, 12, 24).contains(value.cycleMonths()) || value.expectedStewardshipVersion() == null)) {
            throw AnswerCardFailure.invalid();
        }
    }
}
