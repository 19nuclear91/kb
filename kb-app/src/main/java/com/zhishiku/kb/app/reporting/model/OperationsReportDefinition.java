package com.zhishiku.kb.app.reporting.model;

import com.zhishiku.kb.app.reporting.model.ReportQuery.Expr;
import com.zhishiku.kb.app.reporting.model.ReportQuery.Join;
import com.zhishiku.kb.app.reporting.model.ReportQuery.Source;
import com.zhishiku.kb.app.reporting.model.ReportQuery.Table;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.zhishiku.kb.app.reporting.model.ReportQuery.and;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.atOrAfter;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.before;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.choose;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.column;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.count;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.elapsedMillis;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.equal;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.extension;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.finite;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.in;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.literal;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.not;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.or;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.parameter;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.present;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.sum;
import static com.zhishiku.kb.app.reporting.model.ReportQuery.utcDay;

/** OPERATIONS_V1 的固定关系、筛选和聚合定义，不开放自定义查询能力。 */
public final class OperationsReportDefinition {
    public static final List<String> FORMATS = List.of("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "UNKNOWN");
    public static final List<String> PARSING_STATES = List.of("pending", "parsing", "indexed", "failed", "UNKNOWN");
    private static final Expr<String> DOCUMENT_STATUS = column("d", "status", String.class);
    private static final Expr<Boolean> VISIBLE = not(equal(DOCUMENT_STATUS, literal("deleted")));
    private static final Expr<Boolean> ARCHIVED = or(equal(DOCUMENT_STATUS, literal("archived")), column("s", "archived", Boolean.class));
    private static final Table DOCUMENT = new Table("kb_document", "d");
    private static final Table SPACE = new Table("kb_space", "s");
    private static final Join DOCUMENT_SPACE = new Join(SPACE, equal(column("s", "id", Long.class), column("d", "space_id", Long.class)));
    public static final ReportQuery DOCUMENTS = new ReportQuery(new Source(DOCUMENT, List.of(DOCUMENT_SPACE)), VISIBLE,
            Map.of(), Map.of("documentCount", count(literal(true)), "archivedDocumentCount", count(ARCHIVED)));
    public static final ReportQuery VERSIONS = versions();
    public static final ReportQuery PENDING_REVIEWS = new ReportQuery(new Source(new Table("kb_document_review", "r"),
            List.of(new Join(DOCUMENT, equal(column("d", "id", Long.class), column("r", "doc_id", Long.class))), DOCUMENT_SPACE)),
            and(equal(column("r", "state", String.class), literal("IN_REVIEW")), VISIBLE, not(ARCHIVED)),
            Map.of(), Map.of("pendingReviewCount", count(literal(true))));
    public static final ReportQuery EVENTS = events();

    private OperationsReportDefinition() {
    }

    private static ReportQuery versions() {
        Expr<String> suffix = extension(column("v", "file_name", String.class));
        Expr<String> state = column("v", "index_status", String.class);
        return new ReportQuery(new Source(new Table("kb_document_version", "v"),
                List.of(new Join(DOCUMENT, equal(column("d", "id", Long.class), column("v", "doc_id", Long.class))))),
                VISIBLE, Map.of("format", choose(in(suffix, FORMATS.subList(0, FORMATS.size() - 1)), suffix, literal("UNKNOWN")),
                "parsingState", choose(in(state, PARSING_STATES.subList(0, PARSING_STATES.size() - 1)), state, literal("UNKNOWN"))),
                Map.of("versionCount", count(literal(true))));
    }

    private static ReportQuery events() {
        Expr<String> kind = column("e", "kind", String.class);
        Expr<Instant> occurred = column("e", "occurred_at", Instant.class);
        Expr<Instant> submitted = column("e", "submitted_at", Instant.class);
        Expr<Instant> decided = column("e", "decided_at", Instant.class);
        Expr<Boolean> review = in(kind, List.of("REVIEW_APPROVED", "REVIEW_REJECTED"));
        Expr<Boolean> comment = in(kind, List.of("COMMENT_CREATED", "COMMENT_REPLIED"));
        Expr<Boolean> validDuration = and(present(submitted), present(decided), finite(submitted), finite(decided), atOrAfter(decided, submitted));
        return new ReportQuery(new Source(new Table("kb_collaboration_event", "e"), List.of()),
                and(atOrAfter(occurred, parameter("from", Instant.class)), before(occurred, parameter("to", Instant.class)),
                        or(and(review, atOrAfter(occurred, parameter("reviewFrom", Instant.class))),
                                and(comment, atOrAfter(occurred, parameter("commentFrom", Instant.class))))),
                Map.of("day", utcDay(occurred)), Map.of(
                "approvedCount", count(equal(kind, literal("REVIEW_APPROVED"))),
                "rejectedCount", count(equal(kind, literal("REVIEW_REJECTED"))),
                "durationSampleCount", count(and(review, validDuration)),
                "durationUnknownCount", count(and(review, not(validDuration))),
                "decisionElapsedMsTotal", sum(and(review, validDuration), elapsedMillis(submitted, decided)),
                "rootCommentCount", count(equal(kind, literal("COMMENT_CREATED"))),
                "replyCount", count(equal(kind, literal("COMMENT_REPLIED")))));
    }
}
