package com.zhishiku.kb.app.reporting;

import com.zhishiku.kb.app.reporting.model.OperationsReport;
import com.zhishiku.kb.common.review.CollaborationEvent;
import com.zhishiku.kb.common.review.CollaborationKind;
import com.zhishiku.kb.common.review.ReviewRound;
import com.zhishiku.kb.common.review.ReviewState;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.zhishiku.kb.app.reporting.OperationsFactsFixture.FROM;
import static com.zhishiku.kb.app.reporting.OperationsFactsFixture.TO;
import static com.zhishiku.kb.app.reporting.OperationsFactsFixture.coverage;
import static com.zhishiku.kb.app.reporting.OperationsFactsFixture.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class OperationsFactsTest {
    @Test
    void inventoryCountsDocumentsOnceAndAllLiveVersionsWithArchiveUnionAndFixedBuckets() {
        var fixture = new OperationsFactsFixture();
        fixture.add("kb_space", "id", 1L, "archived", false);
        fixture.add("kb_space", "id", 2L, "archived", true);
        fixture.add("kb_document", "id", 1L, "space_id", 1L, "status", "published");
        fixture.add("kb_document", "id", 2L, "space_id", 2L, "status", "draft");
        fixture.add("kb_document", "id", 3L, "space_id", 1L, "status", "deleted");
        fixture.add("kb_document", "id", 4L, "space_id", 2L, "status", "archived");
        fixture.add("kb_document", "id", 5L, "space_id", 1L, "status", "archived");
        fixture.add("kb_document_version", "id", 11L, "doc_id", 1L, "file_name", "published.PDF", "index_status", "indexed");
        fixture.add("kb_document_version", "id", 12L, "doc_id", 1L, "file_name", "candidate.pdf", "index_status", "failed");
        fixture.add("kb_document_version", "id", 21L, "doc_id", 2L, "file_name", "new.DOCX", "index_status", "pending");
        fixture.add("kb_document_version", "id", 31L, "doc_id", 3L, "file_name", "deleted.md", "index_status", "indexed");
        fixture.add("kb_document_version", "id", 41L, "doc_id", 4L, "file_name", "legacy.other", "index_status", "unrecognized");
        fixture.add("kb_document_version", "id", 51L, "doc_id", 5L, "file_name", null, "index_status", "parsing");
        for (int chunk = 0; chunk < 100; chunk++) {
            fixture.add("kb_chunk", "version_id", 11L, "chunk_id", chunk);
        }
        var report = fixture.read();
        assertThat(report.inventory().documentCount()).isEqualTo("4");
        assertThat(report.inventory().archivedDocumentCount()).isEqualTo("3");
        assertThat(report.inventory().versionCount()).isEqualTo("5");
        assertThat(report.inventory().formats()).containsEntry("pdf", "2").containsEntry("docx", "1")
                .containsEntry("md", "0").containsEntry("UNKNOWN", "2");
        assertThat(report.inventory().parsingStates()).containsEntry("indexed", "1").containsEntry("failed", "1")
                .containsEntry("pending", "1").containsEntry("parsing", "1").containsEntry("UNKNOWN", "1");
        assertThat(sum(report.inventory().formats())).isEqualTo(new BigInteger(report.inventory().versionCount()));
        assertThat(sum(report.inventory().parsingStates())).isEqualTo(new BigInteger(report.inventory().versionCount()));
        assertThat(fixture.reports.read(Map.of()).inventory()).isEqualTo(report.inventory());
    }

    @Test
    void pendingReviewsExcludeArchivedOrDeletedTargetsAndDoNotInferRoundsFromDocumentStatus() {
        var fixture = new OperationsFactsFixture();
        fixture.add("kb_space", "id", 1L, "archived", false);
        fixture.add("kb_space", "id", 2L, "archived", true);
        for (int index = 0; index < 6; index++) {
            fixture.add("kb_document", "id", (long) index + 1, "space_id", index == 4 ? 2L : 1L,
                    "status", List.of("draft", "in_review", "archived", "deleted", "published", "in_review").get(index));
        }
        for (long document = 1; document <= 5; document++) {
            fixture.add("kb_document_review", "doc_id", document, "state", "IN_REVIEW");
        }
        for (String state : List.of("APPROVED", "REJECTED", "CANCELLED", "WITHDRAWN")) {
            fixture.add("kb_document_review", "doc_id", 1L, "state", state);
        }
        assertThat(fixture.read().review().pendingReviewCount()).isEqualTo("2");
        assertThat(fixture.reports.read(Map.of()).review().pendingReviewCount()).isEqualTo("2");
        assertThat(fixture.read().review().approvedCount()).isEqualTo("0");
    }

    @Test
    void crossWindowRoundsDeduplicateAndPublicationWithdrawalCancellationDoNotAddDecisions() {
        var fixture = new OperationsFactsFixture();
        fixture.add("kb_space", "id", 1L, "archived", false);
        var document = fixture.add("kb_document", "id", 1L, "space_id", 1L, "status", "published");
        var approved = review(CollaborationKind.REVIEW_APPROVED, 1, FROM.plusSeconds(60), FROM.minusSeconds(60));
        assertThat(fixture.producer.append(approved, List.of(9L))).isTrue();
        assertThat(fixture.producer.append(approved, List.of(9L))).isFalse();
        fixture.producer.append(review(CollaborationKind.REVIEW_REJECTED, 2, FROM.plusSeconds(7200), FROM.plusSeconds(3600)), List.of());
        fixture.producer.append(CollaborationEvent.create(CollaborationKind.VERSION_PUBLISHED, 1, 11,
                null, 8L, FROM.plusSeconds(60), null), List.of());
        fixture.producer.append(review(CollaborationKind.REVIEW_WITHDRAWN, 3, FROM.plusSeconds(10800), FROM.plusSeconds(9000)), List.of());
        fixture.producer.append(review(CollaborationKind.REVIEW_CANCELLED, 4, FROM.plusSeconds(14400), FROM.plusSeconds(12600)), List.of());
        var report = fixture.read();
        assertThat(report.review().approvedCount()).isEqualTo("1");
        assertThat(report.review().rejectedCount()).isEqualTo("1");
        assertThat(report.review().durationSampleCount()).isEqualTo("2");
        assertThat(report.review().decisionElapsedMsTotal()).isEqualTo("3720000");
        assertThat(report.review().pendingReviewCount()).isEqualTo("0");
        document.put("status", "deleted");
        document.put("space_id", 2L);
        fixture.add("kb_space", "id", 2L, "archived", true);
        assertThat(fixture.read().review()).isEqualTo(report.review());
        assertThat(fixture.read().inventory().documentCount()).isEqualTo("0");
    }

    @Test
    void commentCreationHistorySurvivesBodyDeletionDocumentInvalidationReplayAndNotificationDelivery() {
        var fixture = new OperationsFactsFixture();
        fixture.add("kb_space", "id", 1L, "archived", false);
        var document = fixture.add("kb_document", "id", 1L, "space_id", 1L, "status", "published");
        UUID root = UUID.randomUUID(), reply = UUID.randomUUID();
        var rootRow = fixture.add("kb_document_comment", "comment_id", root, "body", "根正文");
        var replyRow = fixture.add("kb_document_comment", "comment_id", reply, "body", "回复正文");
        var created = CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 1, 11, root, null, 8, FROM);
        var replied = CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, 1, 11, reply, root, 9, TO.minusNanos(1000));
        fixture.producer.append(created, List.of());
        fixture.producer.append(replied, List.of(8L));
        var before = fixture.read();
        assertThat(before.comments().rootCommentCount()).isEqualTo("1");
        assertThat(before.comments().replyCount()).isEqualTo("1");
        rootRow.put("body", null);
        replyRow.put("body", null);
        document.put("status", "deleted");
        document.put("current_version_id", 12L);
        assertThat(fixture.producer.append(created, List.of())).isFalse();
        assertThat(fixture.producer.append(replied, List.of(8L))).isFalse();
        fixture.producer.append(CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED, 1, 11, root, null, 8, TO.minusSeconds(1)), List.of());
        fixture.producer.append(CollaborationEvent.comment(CollaborationKind.COMMENT_DELETED, 1, 11, reply, root, 9, TO.minusSeconds(1)), List.of());
        fixture.producer.append(review(CollaborationKind.REVIEW_REJECTED, 1, FROM.plusSeconds(30), FROM), List.of(8L));
        for (int delivery = 0; delivery < 10; delivery++) {
            fixture.add("kb_user_notification", "event_id", replied.eventId(), "user_id", 8L, "read", delivery > 0);
        }
        assertThat(fixture.read().comments()).isEqualTo(before.comments());
        assertThat(fixture.read().review().rejectedCount()).isEqualTo("1");
    }

    @Test
    void eachDurationIsFlooredBeforeSummationAndMalformedTimesOnlyAddUnknownSamples() {
        var fixture = new OperationsFactsFixture();
        for (int round = 1; round <= 3; round++) {
            Instant decision = FROM.plusSeconds(round);
            long nanos = round == 3 ? 999_000 : 1_999_000;
            fixture.producer.append(review(round == 2 ? CollaborationKind.REVIEW_REJECTED : CollaborationKind.REVIEW_APPROVED,
                    round, decision, decision.minusNanos(nanos)), List.of());
        }
        fixture.append("missing-submission", rawDecision(FROM.plusSeconds(10), null, FROM.plusSeconds(10)));
        fixture.append("missing-decision", rawDecision(FROM.plusSeconds(11), FROM, null));
        fixture.append("negative", rawDecision(FROM.plusSeconds(12), FROM.plusSeconds(13), FROM.plusSeconds(12)));
        fixture.append("infinite-submission", rawDecision(FROM.plusSeconds(14), Instant.MIN, FROM.plusSeconds(14)));
        fixture.append("infinite-decision", rawDecision(FROM.plusSeconds(15), FROM, Instant.MAX));
        var report = fixture.read();
        assertThat(report.review().approvedCount()).isEqualTo("7");
        assertThat(report.review().rejectedCount()).isEqualTo("1");
        assertThat(report.review().durationSampleCount()).isEqualTo("3");
        assertThat(report.review().durationUnknownCount()).isEqualTo("5");
        assertThat(report.review().decisionElapsedMsTotal()).isEqualTo("2");
        assertThat(report.review().meanDecisionMs()).isEqualTo("0.67");
    }

    @Test
    void noValidSampleKeepsMeanNullEvenWhenDecisionsExist() {
        var fixture = new OperationsFactsFixture();
        assertThat(fixture.read().review().meanDecisionMs()).isNull();
        fixture.append("unknown", rawDecision(FROM.plusSeconds(1), null, FROM.plusSeconds(1)));
        var report = fixture.read();
        assertThat(report.review().durationSampleCount()).isEqualTo("0");
        assertThat(report.review().durationUnknownCount()).isEqualTo("1");
        assertThat(report.review().decisionElapsedMsTotal()).isEqualTo("0");
        assertThat(report.review().meanDecisionMs()).isNull();
    }

    @Test
    void actualBoundaryFactsUseHalfOpenMicrosAndIndependentCoverageAcrossUtcMidnight() {
        var fixture = new OperationsFactsFixture();
        Instant from = TO.minusNanos(1000), to = TO.plusNanos(1000);
        when(fixture.mapper.coverage()).thenReturn(List.of(coverage("REVIEW", FROM), coverage("COMMENT", TO)));
        fixture.producer.append(review(CollaborationKind.REVIEW_APPROVED, 1, from, from.minusNanos(1000)), List.of());
        fixture.producer.append(review(CollaborationKind.REVIEW_APPROVED, 2, from.minusNanos(1000), FROM), List.of());
        fixture.producer.append(review(CollaborationKind.REVIEW_APPROVED, 3, to, FROM), List.of());
        UUID root = UUID.randomUUID();
        fixture.producer.append(CollaborationEvent.comment(CollaborationKind.COMMENT_CREATED, 1, 11, root, null, 8, TO), List.of());
        fixture.producer.append(CollaborationEvent.comment(CollaborationKind.COMMENT_REPLIED, 1, 11, UUID.randomUUID(), root, 9, to), List.of());
        var report = fixture.reports.read(Map.of("from", List.of("2026-10-27T07:59:59.999999+08:00"),
                "to", List.of("2026-10-27T08:00:00.000001+08:00")));
        assertThat(report.from()).isEqualTo(from.toString());
        assertThat(report.to()).isEqualTo(to.toString());
        assertThat(report.review().approvedCount()).isEqualTo("1");
        assertThat(report.comments().rootCommentCount()).isEqualTo("1");
        assertThat(report.comments().replyCount()).isEqualTo("0");
        assertThat(report.days()).hasSize(2);
        assertThat(report.days().getFirst().comments().rootCommentCount()).isNull();
        assertThat(report.days().getLast().comments().rootCommentCount()).isEqualTo("1");
        assertThat(report.days()).allMatch(OperationsReport.Day::partialDay);
        assertThat(report.comments().coverage().status()).isEqualTo("PARTIAL");
        assertThat(report.days().stream().map(day -> new BigInteger(day.review().approvedCount()))
                .reduce(BigInteger.ZERO, BigInteger::add).toString()).isEqualTo(report.review().approvedCount());
    }

    private static CollaborationEvent review(CollaborationKind kind, int round, Instant decision, Instant submitted) {
        var identity = new ReviewRound(UUID.randomUUID(), 1, 11, round, 8, 1, submitted, ReviewState.IN_REVIEW);
        return CollaborationEvent.create(kind, 1, 11, identity, 9L, decision, decision);
    }

    private static Map<String, Object> rawDecision(Instant occurred, Instant submitted, Instant decided) {
        return row("kind", "REVIEW_APPROVED", "occurred_at", occurred, "submitted_at", submitted, "decided_at", decided);
    }

    private static BigInteger sum(Map<String, String> values) {
        return values.values().stream().map(BigInteger::new).reduce(BigInteger.ZERO, BigInteger::add);
    }
}
