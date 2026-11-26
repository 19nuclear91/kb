package com.zhishiku.kb.app.service.impl.answercard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.mapper.PermissionMapper;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.answercard.AnswerCard;
import com.zhishiku.kb.app.domain.answercard.AnswerCardBranch;
import com.zhishiku.kb.app.domain.answercard.AnswerCardCommand;
import com.zhishiku.kb.app.domain.answercard.AnswerCardConfirmation;
import com.zhishiku.kb.app.domain.answercard.AnswerCardRevision;
import com.zhishiku.kb.app.domain.answercard.AnswerCardReview;
import com.zhishiku.kb.app.domain.answercard.AnswerCardSource;
import com.zhishiku.kb.app.domain.answercard.AnswerCardStewardship;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardContent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardDates;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardIntent;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceLocation;
import com.zhishiku.kb.app.domain.vo.answercard.AnswerCardReceipt;
import com.zhishiku.kb.app.enums.answercard.AnswerCardOperation;
import com.zhishiku.kb.app.enums.answercard.AnswerCardReviewState;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardCommandMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardMapper;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardQueryMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.service.answercard.AnswerCardWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 内存事务管理器只证明服务边界及失败传播，不模拟PostgreSQL锁或约束。 */
class AnswerCardWorkflowTest {
    private final AnswerCardMapper mapper = mock(AnswerCardMapper.class);
    private final AnswerCardQueryMapper queries = mock(AnswerCardQueryMapper.class);
    private final AnswerCardAccess access = mock(AnswerCardAccess.class);
    private final AnswerCardCommandMapper receipts = mock(AnswerCardCommandMapper.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final Map<Long, AnswerCard> cards = new HashMap<>();
    private final Map<Long, AnswerCardBranch> branches = new HashMap<>();
    private final Map<Long, AnswerCardRevision> revisions = new HashMap<>();
    private final Map<Long, List<AnswerCardSource>> bindings = new HashMap<>();
    private final Map<Long, Set<Long>> contributors = new HashMap<>();
    private final Map<Long, AnswerCardReview> rounds = new HashMap<>();
    private final Map<Long, AnswerCardStewardship> settings = new HashMap<>();
    private final Map<String, AnswerCardCommand> commands = new HashMap<>();
    private final List<AnswerCardConfirmation> confirmations = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong(10);
    private final Set<Long> denied = new HashSet<>();
    private final OffsetDateTime now = OffsetDateTime.parse("2026-11-30T10:00:00+08:00");
    private AnswerCardWriteService service;
    private long declaration;
    private long currentSource = 102;
    private LocalDate sourceFrom;
    private LocalDate sourceUntil;
    private Runnable beforeCardLock = () -> {};

    @BeforeEach
    void setup() {
        var identities = mock(PermissionMapper.class);
        when(identities.findClearanceLevel(anyLong())).thenReturn((short) 2);
        var permissions = new PermissionService(identities);
        when(access.current(anyLong())).thenAnswer(call -> permissions.principalOf(call.getArgument(0)));
        doAnswer(call -> {
            PermissionService.PrincipalHandle actor = call.getArgument(0);
            if (denied.contains(actor.userId())) {
                throw AnswerCardFailure.forbidden();
            }
            return null;
        }).when(access).requireWriter(any(), anyLong());
        when(access.requireSources(any(), anyList(), anyBoolean())).thenAnswer(call -> {
            List<AnswerCardSourceKey> keys = call.getArgument(1);
            boolean current = call.getArgument(2);
            if (current && keys.stream().anyMatch(key -> key.versionId() != currentSource || key.applicabilityVersion() != declaration)) {
                throw AnswerCardFailure.conflict("SOURCE_CHANGED");
            }
            return keys.stream().map(key -> new AnswerCardSourceFact(key.documentId(), key.versionId(), 9, "制度", 1,
                    currentSource, declaration, sourceFrom, sourceUntil == null ? "UNDECLARED" : "DATE", sourceUntil)).toList();
        });
        when(mapper.now()).thenReturn(now);
        when(mapper.shareTree(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(mapper.shareDocument(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(mapper.shareVersion(anyLong())).thenAnswer(call -> call.getArgument(0));
        when(mapper.sourceLocations(anyList())).thenReturn(List.of(new AnswerCardSourceLocation(101, 9)));
        when(mapper.nextCardId()).thenAnswer(call -> sequence.incrementAndGet());
        when(mapper.nextBranchId()).thenAnswer(call -> sequence.incrementAndGet());
        when(mapper.nextRevisionId()).thenAnswer(call -> sequence.incrementAndGet());
        when(mapper.nextReviewId()).thenAnswer(call -> sequence.incrementAndGet());
        when(mapper.nextRevisionNo(anyLong())).thenAnswer(call -> revisions.values().stream().filter(value -> value.cardId() == (long) call.getArgument(0)).count() + 1);
        when(mapper.nextBranchNo(anyLong())).thenAnswer(call -> branches.values().stream().filter(value -> value.cardId() == (long) call.getArgument(0)).count() + 1);
        when(mapper.nextRoundNo(anyLong())).thenAnswer(call -> rounds.values().stream().filter(value -> value.cardId() == (long) call.getArgument(0)).count() + 1);
        when(mapper.card(anyLong())).thenAnswer(call -> cards.get(call.getArgument(0)));
        when(mapper.lockCard(anyLong())).thenAnswer(call -> {
            beforeCardLock.run();
            return cards.get(call.getArgument(0));
        });
        when(mapper.branch(anyLong())).thenAnswer(call -> branches.get(call.getArgument(0)));
        when(mapper.revision(anyLong())).thenAnswer(call -> revisions.get(call.getArgument(0)));
        when(mapper.review(anyLong())).thenAnswer(call -> rounds.get(call.getArgument(0)));
        when(mapper.activeReview(anyLong())).thenAnswer(call -> rounds.values().stream()
                .filter(value -> value.cardId() == (long) call.getArgument(0) && value.state() == AnswerCardReviewState.IN_REVIEW).findFirst().orElse(null));
        when(mapper.stewardship(anyLong())).thenAnswer(call -> settings.get(call.getArgument(0)));
        when(mapper.sources(anyLong())).thenAnswer(call -> List.copyOf(bindings.getOrDefault(call.getArgument(0), List.of())));
        when(mapper.contributors(anyLong())).thenAnswer(call -> List.copyOf(contributors.getOrDefault(call.getArgument(0), Set.of())));
        when(mapper.insertCard(any())).thenAnswer(call -> {
            AnswerCard row = call.getArgument(0);
            cards.put(row.id(), row);
            return 1;
        });
        when(mapper.insertBranch(any())).thenAnswer(call -> {
            AnswerCardBranch row = call.getArgument(0);
            branches.put(row.id(), row);
            return 1;
        });
        when(mapper.insertRevision(any())).thenAnswer(call -> {
            AnswerCardRevision row = call.getArgument(0);
            revisions.put(row.id(), row);
            return 1;
        });
        when(mapper.insertSource(any())).thenAnswer(call -> {
            AnswerCardSource row = call.getArgument(0);
            var next = new ArrayList<>(bindings.getOrDefault(row.revisionId(), List.of()));
            next.add(row);
            bindings.put(row.revisionId(), List.copyOf(next));
            return 1;
        });
        when(mapper.contribute(anyLong(), anyLong())).thenAnswer(call -> {
            long branchId = call.getArgument(0);
            var next = new HashSet<>(contributors.getOrDefault(branchId, Set.of()));
            next.add(call.getArgument(1));
            contributors.put(branchId, Set.copyOf(next));
            return 1;
        });
        when(mapper.advanceBranch(anyLong(), anyLong())).thenAnswer(call -> {
            AnswerCardBranch row = branches.get(call.getArgument(0));
            branches.put(row.id(), new AnswerCardBranch(row.id(), row.cardId(), row.branchNo(), call.getArgument(1), false));
            return 1;
        });
        when(mapper.advanceCard(anyLong(), anyLong(), any(), any())).thenAnswer(call -> {
            AnswerCard row = cards.get(call.getArgument(0));
            if (row.cardVersion() != (long) call.getArgument(1)) {
                return 0;
            }
            cards.put(row.id(), new AnswerCard(row.id(), row.spaceId(), row.cardVersion() + 1, call.getArgument(2), call.getArgument(3), row.createdAt()));
            return 1;
        });
        when(mapper.closeBranch(anyLong())).thenAnswer(call -> {
            var row = branches.get(call.getArgument(0));
            branches.put(row.id(), new AnswerCardBranch(row.id(), row.cardId(), row.branchNo(), row.latestRevisionId(), true));
            return 1;
        });
        when(mapper.insertReview(any())).thenAnswer(call -> {
            AnswerCardReview row = call.getArgument(0);
            rounds.put(row.id(), row);
            return 1;
        });
        when(mapper.decide(anyLong(), anyLong(), anyString(), anyLong(), any(), any())).thenAnswer(call -> {
            var row = rounds.get(call.getArgument(0));
            if (row.state() != AnswerCardReviewState.IN_REVIEW || row.reviewVersion() != (long) call.getArgument(1)) {
                return 0;
            }
            rounds.put(row.id(), new AnswerCardReview(row.id(), row.cardId(), row.branchId(), row.revisionId(), row.roundNo(),
                    AnswerCardReviewState.valueOf(call.getArgument(2)), row.reviewVersion() + 1, row.submittedBy(), row.submittedAt(),
                    call.getArgument(3), call.getArgument(4), call.getArgument(5)));
            return 1;
        });
        when(mapper.insertStewardship(any())).thenAnswer(call -> {
            AnswerCardStewardship row = call.getArgument(0);
            settings.put(row.cardId(), row);
            return 1;
        });
        when(mapper.updateStewardship(any())).thenAnswer(call -> {
            AnswerCardStewardship row = call.getArgument(0);
            settings.put(row.cardId(), row);
            return 1;
        });
        when(mapper.insertConfirmation(any())).thenAnswer(call -> {
            confirmations.add(call.getArgument(0));
            return 1;
        });
        when(queries.hasReviewer(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(true);
        when(queries.eligible(anyLong(), anyLong(), anyLong(), anyBoolean())).thenReturn(true);
        when(receipts.find(anyString(), anyString())).thenAnswer(call -> commands.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(receipts.insert(any())).thenAnswer(call -> {
            AnswerCardCommand row = call.getArgument(0);
            commands.put(row.operation().name() + ":" + row.requestId(), row);
            return 1;
        });
        var delegate = new AnswerCardWriteServiceImpl(mapper, queries, access,
                new AnswerCardCommands(receipts, new ObjectMapper().findAndRegisterModules()), audit);
        var proxy = new ProxyFactory(delegate);
        proxy.addAdvice(new TransactionInterceptor(new MemoryTransactions(), new AnnotationTransactionAttributeSource()));
        service = (AnswerCardWriteService) proxy.getProxy();
    }

    @Test
    void draftFreezeApprovalAndNewBranchKeepPublishedRevisionIndependent() {
        var created = create();
        long card = id(created.cardId());
        assign(card, 1, 3, 3);
        var submit = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        assertThatThrownBy(() -> run(1, card, AnswerCardOperation.ANSWER_CARD_SAVE, null)).isInstanceOf(AnswerCardFailure.class);
        var approved = run(2, card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submit.reviewId()));
        assertThat(cards.get(card).publishedRevisionId()).isEqualTo(id(approved.revisionId()));
        assertThat(cards.get(card).activeBranchId()).isNull();
        var changed = run(1, card, AnswerCardOperation.ANSWER_CARD_SAVE, null);
        assertThat(changed.branchId()).isNotEqualTo(created.branchId());
        assertThat(cards.get(card).publishedRevisionId()).isEqualTo(id(approved.revisionId()));
        assertThat(revisions).hasSize(2);
    }

    @Test
    void everyContributorAndSubmitterCannotApproveOrReject() {
        long card = id(create().cardId());
        run(4, card, AnswerCardOperation.ANSWER_CARD_SAVE, null);
        assign(card, 1, 3, 3);
        var submitted = run(5, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        for (long actor : List.of(1L, 4L, 5L)) {
            assertThatThrownBy(() -> run(actor, card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submitted.reviewId())))
                    .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.status()).isEqualTo(403));
        }
        run(2, card, AnswerCardOperation.ANSWER_CARD_REJECT, id(submitted.reviewId()));
        assertThat(rounds.get(id(submitted.reviewId())).state()).isEqualTo(AnswerCardReviewState.REJECTED);
        assertThat(cards.get(card).publishedRevisionId()).isNull();
        var next = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        assertThat(next.reviewId()).isNotEqualTo(submitted.reviewId());
    }

    @Test
    void noReviewerAndFirstDateDeclarationBlockPublicationEvenIfDatesRemainUnknown() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        when(queries.hasReviewer(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(false);
        assertThatThrownBy(() -> run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null))
                .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.reason()).isEqualTo("NO_REVIEWER"));
        when(queries.hasReviewer(anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(true);
        var submitted = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        declaration = 1;
        assertThatThrownBy(() -> run(2, card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submitted.reviewId())))
                .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.reason()).isEqualTo("SOURCE_CHANGED"));
        assertThat(cards.get(card).publishedRevisionId()).isNull();
        run(1, card, AnswerCardOperation.ANSWER_CARD_WITHDRAW, id(submitted.reviewId()));
        assertThat(rounds.get(id(submitted.reviewId())).state()).isEqualTo(AnswerCardReviewState.WITHDRAWN);
    }

    @Test
    void successfulApprovalReplaysOriginalBranchBeforeCheckingNewActivity() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var submitted = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        var intent = intent(card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submitted.reviewId()));
        var first = service.execute(2, "approval-lost", intent);
        run(1, card, AnswerCardOperation.ANSWER_CARD_SAVE, null);
        clearInvocations(audit);
        var replay = service.execute(2, "approval-lost", intent);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.revisionId()).isEqualTo(first.revisionId());
        verify(audit, never()).appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), any(), anyString());
        assertThat(service.query(2, "approval-lost", intent)).isEqualTo(replay);
        assertThat(service.query(2, "not-submitted", intent)).isNull();
        doThrow(AnswerCardFailure.forbidden()).when(access).requireReviewer(any(), anyLong());
        assertThatThrownBy(() -> service.query(2, "approval-lost", intent)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void creationReceiptWorksWithoutKnownCardAndCannotOutlivePermission() {
        var intent = new AnswerCardIntent(AnswerCardOperation.ANSWER_CARD_CREATE, 9, null, null, null, null,
                null, null, null, null, null, null, content());
        var created = service.execute(1, "create-lost", intent);
        assertThat(service.query(1, "create-lost", intent).cardId()).isEqualTo(created.cardId());
        denied.add(1L);
        assertThatThrownBy(() -> service.query(1, "create-lost", intent)).isInstanceOf(AnswerCardFailure.class);
        assertThat(cards).hasSize(1);
    }

    @Test
    void lockInterleavingAndLateSecondDecisionCannotOverwriteTerminalFact() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var submit = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        var old = intent(card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submit.reviewId()));
        run(1, card, AnswerCardOperation.ANSWER_CARD_WITHDRAW, id(submit.reviewId()));
        assertThatThrownBy(() -> service.execute(2, "late", old)).isInstanceOf(AnswerCardFailure.class);
        assertThat(rounds.get(id(submit.reviewId())).state()).isEqualTo(AnswerCardReviewState.WITHDRAWN);
        beforeCardLock = () -> {
            var row = cards.get(card);
            cards.put(card, new AnswerCard(card, 9, row.cardVersion() + 1, row.publishedRevisionId(), row.activeBranchId(), now));
        };
        assertThatThrownBy(() -> run(1, card, AnswerCardOperation.ANSWER_CARD_SAVE, null))
                .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.reason()).isEqualTo("CARD_VERSION_CONFLICT"));
    }

    @Test
    void auditOrReceiptFailureRollsBackAllMemoryFactsThroughActualServiceTransactionAdvice() {
        doThrow(new IllegalStateException("private dependency detail")).when(audit)
                .appendSuccess(any(), anyString(), anyLong(), anyLong(), anyString(), any(), anyString());
        assertThatThrownBy(this::create).isInstanceOf(IllegalStateException.class);
        assertThat(cards).isEmpty();
        assertThat(revisions).isEmpty();
        assertThat(commands).isEmpty();
    }

    @Test
    void reassignmentKeepsBaselineAndConfirmationCannotBypassSourcesOrNewDraft() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var submit = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        run(2, card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submit.reviewId()));
        assign(card, 1, 4, 6);
        assertThat(settings.get(card).firstAssignedOn()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(AnswerCardRules.dueDate(settings.get(card))).isEqualTo(LocalDate.of(2027, 5, 30));
        assertThatThrownBy(() -> run(3, card, AnswerCardOperation.ANSWER_CARD_CONFIRM, null)).isInstanceOf(AnswerCardFailure.class);
        currentSource = 103;
        assertThatThrownBy(() -> run(4, card, AnswerCardOperation.ANSWER_CARD_CONFIRM, null)).isInstanceOf(AnswerCardFailure.class);
        currentSource = 102;
        var confirmation = intent(card, AnswerCardOperation.ANSWER_CARD_CONFIRM, null);
        service.execute(4, "confirm", confirmation);
        service.execute(4, "confirm", confirmation);
        assertThat(confirmations).hasSize(1);
        assertThat(revisions.get(cards.get(card).publishedRevisionId()).effectiveUntil()).isNull();
        run(1, card, AnswerCardOperation.ANSWER_CARD_SAVE, null);
        assertThatThrownBy(() -> run(4, card, AnswerCardOperation.ANSWER_CARD_CONFIRM, null)).isInstanceOf(AnswerCardFailure.class);
    }

    @Test
    void receiptFailureRestoresExistingDraftAndContributorSetInMemoryTransaction() {
        long card = id(create().cardId());
        var before = cards.get(card);
        doReturn(0).when(receipts).insert(any());
        assertThatThrownBy(() -> run(4, card, AnswerCardOperation.ANSWER_CARD_SAVE, null)).isInstanceOf(AnswerCardFailure.class);
        assertThat(cards.get(card)).isEqualTo(before);
        assertThat(revisions).hasSize(1);
        assertThat(contributors.get(before.activeBranchId())).containsExactly(1L);
        assertThat(commands).hasSize(1);
    }

    @Test
    void twoReviewersAndStewardRevocationCannotProduceSecondOrPartialPublication() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var submitted = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        var original = intent(card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submitted.reviewId()));
        when(queries.eligible(eq(card), anyLong(), eq(3L), eq(false))).thenReturn(false);
        assertThatThrownBy(() -> service.execute(2, "ineligible", original)).isInstanceOf(AnswerCardFailure.class);
        assertThat(cards.get(card).publishedRevisionId()).isNull();
        when(queries.eligible(eq(card), anyLong(), eq(3L), eq(false))).thenReturn(true);
        service.execute(2, "first-reviewer", original);
        assertThatThrownBy(() -> service.execute(4, "second-reviewer", original)).isInstanceOf(AnswerCardFailure.class);
        assertThat(rounds.get(id(submitted.reviewId())).decidedBy()).isEqualTo(2);
    }

    @Test
    void futureSourceCanBeReviewedButExpiredIntersectionCannotBeSubmitted() {
        sourceFrom = LocalDate.of(2027, 1, 1);
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var submitted = run(1, card, AnswerCardOperation.ANSWER_CARD_SUBMIT, null);
        run(2, card, AnswerCardOperation.ANSWER_CARD_APPROVE, id(submitted.reviewId()));
        assertThat(cards.get(card).publishedRevisionId()).isNotNull();
        assertThatThrownBy(() -> run(3, card, AnswerCardOperation.ANSWER_CARD_CONFIRM, null)).isInstanceOf(AnswerCardFailure.class);
        sourceFrom = null;
        sourceUntil = LocalDate.of(2026, 11, 29);
        long expired = id(create().cardId());
        assign(expired, 1, 3, 3);
        assertThatThrownBy(() -> run(1, expired, AnswerCardOperation.ANSWER_CARD_SUBMIT, null))
                .isInstanceOfSatisfying(AnswerCardFailure.class, failure -> assertThat(failure.reason()).isEqualTo("NO_APPLICABLE_DATE"));
    }

    @Test
    void changedSourceLocationAndArchivedTargetAbortBeforeCreatingAnyCard() {
        when(mapper.sourceLocations(anyList())).thenReturn(List.of(new AnswerCardSourceLocation(101, 9)), List.of(new AnswerCardSourceLocation(101, 10)));
        assertThatThrownBy(this::create).isInstanceOfSatisfying(AnswerCardFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo("SOURCE_CHANGED"));
        assertThat(cards).isEmpty();
        doThrow(AnswerCardFailure.hidden()).when(access).requireWriter(any(), eq(9L));
        assertThatThrownBy(this::create).isInstanceOf(AnswerCardFailure.class);
        assertThat(cards).isEmpty();
    }

    @Test
    void deniedCandidatePreservesResponsibilityAndCalendarBaseline() {
        long card = id(create().cardId());
        assign(card, 1, 3, 3);
        var before = settings.get(card);
        when(queries.eligible(eq(card), anyLong(), eq(4L), eq(false))).thenReturn(false);
        assertThatThrownBy(() -> assign(card, 1, 4, 12)).isInstanceOf(AnswerCardFailure.class);
        assertThat(settings.get(card)).isEqualTo(before);
    }

    private AnswerCardReceipt create() {
        return service.execute(1, "create-" + sequence.incrementAndGet(), new AnswerCardIntent(AnswerCardOperation.ANSWER_CARD_CREATE,
                9, null, null, null, null, null, null, null, null, null, null, content()));
    }

    private void assign(long card, long actor, long responsible, int months) {
        var row = cards.get(card);
        var setting = settings.get(card);
        service.execute(actor, "assign-" + sequence.incrementAndGet(), new AnswerCardIntent(AnswerCardOperation.ANSWER_CARD_STEWARDSHIP_UPDATE,
                9, card, null, null, null, row.cardVersion(), null, setting == null ? 0L : setting.stewardshipVersion(),
                responsible, months, null, null));
    }

    private AnswerCardReceipt run(long actor, long card, AnswerCardOperation operation, Long review) {
        return service.execute(actor, "request-" + sequence.incrementAndGet(), intent(card, operation, review));
    }

    private AnswerCardIntent intent(long card, AnswerCardOperation operation, Long review) {
        var row = cards.get(card);
        boolean confirm = operation == AnswerCardOperation.ANSWER_CARD_CONFIRM;
        Long branch = confirm ? null : row.activeBranchId();
        Long revision = branch == null ? row.publishedRevisionId() : branches.get(branch).latestRevisionId();
        return new AnswerCardIntent(operation, 9, card, branch, revision, review, row.cardVersion(),
                review == null ? null : rounds.get(review).reviewVersion(), confirm ? settings.get(card).stewardshipVersion() : null,
                null, null, operation == AnswerCardOperation.ANSWER_CARD_REJECT ? "请核对适用范围" : null,
                operation == AnswerCardOperation.ANSWER_CARD_SAVE ? content() : null);
    }

    private AnswerCardContent content() {
        return new AnswerCardContent("问题", List.of("常见问法"), "审定答案", "适用范围", new AnswerCardDates(null, "UNDECLARED", null),
                List.of(new AnswerCardSourceKey(101, currentSource, declaration)));
    }

    private static long id(String value) {
        return Long.parseLong(value);
    }

    private final class MemoryTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            var maps = List.<Map<?, ?>>of(cards, branches, revisions, bindings, contributors, rounds, settings, commands);
            var restore = new ArrayList<Runnable>();
            maps.forEach(map -> restore.add(snapshot(map)));
            var previousConfirmations = List.copyOf(confirmations);
            restore.add(() -> {
                confirmations.clear();
                confirmations.addAll(previousConfirmations);
            });
            return restore;
        }

        private <K, V> Runnable snapshot(Map<K, V> map) {
            var previous = new HashMap<>(map);
            return () -> {
                map.clear();
                map.putAll(previous);
            };
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            assertThat(definition.getTimeout()).isIn(5, 10);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doRollback(DefaultTransactionStatus status) {
            ((List<Runnable>) status.getTransaction()).forEach(Runnable::run);
        }
    }
}
