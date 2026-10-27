package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.mapper.DocumentReviewQueryMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.PrivateReviewView;
import com.zhishiku.kb.app.document.model.ReviewListRow;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.app.document.model.ReviewViews;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.app.query.QueryParameters;
import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.common.review.PublicationEvidence;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.PublicationProtocol;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import com.zhishiku.kb.common.review.ReviewState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true, timeout = 10)
public class DocumentReviewQueryService {
    private final DocumentReviewQueryMapper queries;
    private final DocumentReviewMapper mapper;
    private final ReviewAuthorizationService authorization;
    private final ReviewEligibilityService eligibility;
    private final EmbeddingControlService embedding;
    private final PublicationReadinessService readiness;

    public DocumentReviewQueryService(DocumentReviewQueryMapper queries, DocumentReviewMapper mapper,
                                       ReviewAuthorizationService authorization, ReviewEligibilityService eligibility,
                                       EmbeddingControlService embedding, PublicationReadinessService readiness) {
        this.queries = queries;
        this.mapper = mapper;
        this.authorization = authorization;
        this.eligibility = eligibility;
        this.embedding = embedding;
        this.readiness = readiness;
    }

    public ReviewViews.Page list(long actor, Map<String, List<String>> input) {
        authorization.requireEnabled(actor);
        var params = new QueryParameters(input, "kind", "limit", "cursor");
        String kind = params.exactText("kind", 7);
        if (!"mine".equals(kind) && !"pending".equals(kind)) throw QueryException.invalid();
        String cursorKind = "document-review-" + kind;
        var filters = QueryParameters.filters("actor", actor, "kind", kind);
        var cursor = QueryCursor.decode(params.cursor(), cursorKind);
        if (cursor != null) cursor.verify(cursorKind, filters);
        int limit = params.limit();
        var rows = kind.equals("mine") ? queries.mine(actor, cursor == null ? null : cursor.id(), limit + 1)
                : queries.pending(actor, cursor == null ? null : cursor.id(), cursor == null ? null : cursor.time(), limit + 1);
        if (rows == null || rows.size() > limit + 1) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
        boolean more = rows.size() > limit;
        var page = rows.subList(0, Math.min(limit, rows.size()));
        String next = more ? QueryCursor.encode(cursorKind, filters, page.getLast().getVersionId(),
                kind.equals("pending") ? page.getLast().getSubmittedAt() : null, null, null) : null;
        return new ReviewViews.Page(page.stream().map(row -> new ReviewViews.Item(listState(row),
                row.isCanRead(), kind.equals("pending") ? row.getTitle() : null)).toList(), more, next);
    }

    public ReviewViews.Status status(long actor, long documentId, long versionId, Map<String, List<String>> input) {
        authorization.requireEnabled(actor);
        var params = new QueryParameters(input, "opinions", "limit", "cursor");
        boolean opinions = Boolean.TRUE.equals(params.bool("opinions"));
        int limit = params.limit();
        var filters = QueryParameters.filters("actor", actor, "document", documentId, "version", versionId);
        var cursor = QueryCursor.decode(params.cursor(), "document-review-private");
        if (cursor != null) cursor.verify("document-review-private", filters);
        if (!opinions && (cursor != null || input.containsKey("limit"))) throw QueryException.invalid();
        var version = mapper.version(documentId, versionId);
        if (version == null || !version.live()) throw new ReviewFailure(ReviewError.NOT_FOUND);
        var round = mapper.latest(versionId);
        boolean mine = Objects.equals(version.getUploaderUserId(), actor) || mapper.hasSubmitted(versionId, actor);
        boolean view = authorization.can(actor, documentId, PermissionService.PERM_VIEW);
        boolean privateAccess = view && authorization.participant(actor, version, round);
        boolean readable = view && (version.getPublicationEvidence().isPublished()
                || version.getPublicationProtocol() == PublicationProtocol.LEGACY_COMPAT || privateAccess);
        if (!mine && !readable) throw new ReviewFailure(ReviewError.NOT_FOUND);
        if (opinions && !privateAccess) throw new ReviewFailure(ReviewError.NOT_FOUND);
        boolean edit = authorization.can(actor, documentId, PermissionService.PERM_EDIT);
        boolean active = round != null && round.getState() == ReviewState.IN_REVIEW && round.getSubmittedSpaceId() == version.getSpaceId();
        boolean unpublished = version.getPublicationEvidence() == PublicationEvidence.NONE;
        boolean indexed = "indexed".equals(version.getIndexStatus());
        EmbeddingControl control = embedding.current();
        boolean stale = unpublished && indexed && readiness.needsRefresh(version, control);
        boolean reviewer = readable && active && eligibility.eligible(actor, documentId, version.getUploaderUserId(), round.getSubmittedBy());
        var capabilities = new ReviewViews.Capabilities(readable,
                readable && authorization.can(actor, documentId, PermissionService.PERM_DOWNLOAD),
                edit && indexed && unpublished && !active && version.getPublicationPolicy() == PublicationPolicy.REVIEW_REQUIRED,
                reviewer && readiness.ready(version, control, ReviewState.APPROVED),
                reviewer, edit && active && round.getSubmittedBy() == actor,
                edit && unpublished && indexed && stale, privateAccess);
        List<PrivateReviewView> rounds = List.of();
        boolean more = false;
        String next = null;
        if (opinions) {
            var rows = mapper.privateRounds(versionId, cursor == null ? Long.MAX_VALUE : cursor.id(), limit + 1);
            if (rows == null || rows.size() > limit + 1) throw new ReviewFailure(ReviewError.SERVICE_UNAVAILABLE);
            more = rows.size() > limit;
            var page = rows.subList(0, Math.min(limit, rows.size()));
            rounds = page.stream().map(PrivateReviewView::from).toList();
            next = more ? QueryCursor.encode("document-review-private", filters, page.getLast().getRoundNo()) : null;
        }
        return new ReviewViews.Status(state(version, round, readable,
                !control.active() ? "EMBEDDING_MAINTENANCE" : stale ? "INDEX_REFRESH_REQUIRED" : null),
                capabilities, rounds, more, next);
    }

    private static ReviewViews.State listState(ReviewListRow row) {
        return new ReviewViews.State(row.getDocumentId(), row.getVersionId(), row.getVersionNo(), row.getStateVersion(),
                row.getIndexStatus(), row.getPublicationPolicy(), row.getPublicationEvidence(),
                row.getReviewState() == null ? "DRAFT" : row.getReviewState().name(), row.getReviewId(), row.getRoundNo(), null, null);
    }

    private static ReviewViews.State state(ReviewVersionState version, DocumentReviewRow round, boolean readable, String waiting) {
        return new ReviewViews.State(version.getDocumentId(), version.getVersionId(), version.getVersionNo(), version.getStateVersion(),
                version.getIndexStatus(), version.getPublicationPolicy(), version.getPublicationEvidence(),
                round == null ? "DRAFT" : round.getState().name(), round == null ? null : round.getId(),
                round == null ? null : round.getRoundNo(), readable ? version.getCurrentVersionId() : null, waiting);
    }
}
