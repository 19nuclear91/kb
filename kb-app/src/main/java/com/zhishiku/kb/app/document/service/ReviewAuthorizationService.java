package com.zhishiku.kb.app.document.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.mapper.DocumentReviewMapper;
import com.zhishiku.kb.app.document.model.DocumentReviewRow;
import com.zhishiku.kb.app.document.model.ReviewVersionState;
import com.zhishiku.kb.common.review.ReviewError;
import com.zhishiku.kb.common.review.ReviewFailure;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;

/** 当前资源资格边界，任何参与者身份都不能代替 view 或 edit。 */
@Service
public class ReviewAuthorizationService {
    private final PermissionService permissions;
    private final DocumentReviewMapper mapper;
    private final ReviewEligibilityService eligibility;

    public ReviewAuthorizationService(PermissionService permissions, DocumentReviewMapper mapper,
                                        ReviewEligibilityService eligibility) {
        this.permissions = permissions;
        this.mapper = mapper;
        this.eligibility = eligibility;
    }

    public void requireEnabled(long actor) {
        permissions.principalOf(actor).requireEnabled();
    }

    public boolean can(long actor, long documentId, String bit) {
        var handle = permissions.principalOf(actor);
        handle.requireEnabled();
        var scope = permissions.resolve(handle, bit);
        if (scope.principalKeys().length == 0) return false;
        return mapper.hasPermission(documentId, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel(), bit);
    }

    public void requireEdit(long actor, ReviewVersionState version) {
        if (version == null || !version.live() || !can(actor, version.getDocumentId(), PermissionService.PERM_EDIT))
            throw new ReviewFailure(ReviewError.NOT_FOUND);
    }

    public void requireReviewer(long actor, ReviewVersionState version, DocumentReviewRow round) {
        if (version == null || round == null || !version.live()
                || round.getDocId() != version.getDocumentId() || round.getVersionId() != version.getVersionId()
                || !eligibility.eligible(actor, version.getDocumentId(), version.getUploaderUserId(), round.getSubmittedBy()))
            throw new ReviewFailure(ReviewError.NOT_FOUND);
    }

    public boolean participant(long actor, ReviewVersionState version, DocumentReviewRow current) {
        return Objects.equals(version.getUploaderUserId(), actor) || mapper.hasSubmitted(version.getVersionId(), actor)
                || eligibility.eligible(actor, version.getDocumentId(), version.getUploaderUserId(),
                current == null ? null : current.getSubmittedBy());
    }

    public boolean privateAccess(long actor, ReviewVersionState version, DocumentReviewRow current) {
        return version != null && version.live() && can(actor, version.getDocumentId(), PermissionService.PERM_VIEW)
                && participant(actor, version, current);
    }
}
