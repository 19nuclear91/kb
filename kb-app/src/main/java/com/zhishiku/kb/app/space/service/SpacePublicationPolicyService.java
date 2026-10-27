package com.zhishiku.kb.app.space.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.collaboration.service.CommandReceiptService;
import com.zhishiku.kb.app.space.mapper.SpacePublicationPolicyMapper;
import com.zhishiku.kb.app.space.model.SpacePublicationPolicy;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.review.PublicationPolicy;
import com.zhishiku.kb.common.review.ReviewCommandBinding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SpacePublicationPolicyService {
    private final SpacePublicationPolicyMapper mapper;
    private final SpaceAuthorizer authorizer;
    private final CommandReceiptService receipts;
    private final IdentityAuditService audit;

    public SpacePublicationPolicyService(SpacePublicationPolicyMapper mapper, SpaceAuthorizer authorizer,
                                          CommandReceiptService receipts, IdentityAuditService audit) {
        this.mapper = mapper;
        this.authorizer = authorizer;
        this.receipts = receipts;
        this.audit = audit;
    }

    public SpacePublicationPolicy get(long spaceId) {
        authorizer.requireSpaceManager(spaceId);
        var state = mapper.find(spaceId);
        if (state == null) throw ApiException.notFound("空间不存在");
        return state;
    }

    @Transactional
    public SpacePublicationPolicy update(long spaceId, PublicationPolicy policy, long expectedVersion, String requestId) {
        long actor = authorizer.requireSpaceManager(spaceId);
        ReviewCommandBinding binding;
        try {
            binding = ReviewCommandBinding.policy(requestId, actor, spaceId, expectedVersion, policy);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw ApiException.badRequest("审核策略请求参数无效");
        }
        if (mapper.find(spaceId) == null) throw ApiException.notFound("空间不存在");
        String previous = receipts.claim(binding);
        if (previous != null) return receipts.read(previous, SpacePublicationPolicy.class);
        var before = mapper.lock(spaceId);
        if (before == null) throw ApiException.notFound("空间不存在");
        if (before.version() != expectedVersion) throw ApiException.conflict("STATE_CONFLICT");
        if (mapper.update(spaceId, policy, expectedVersion) != 1) throw ApiException.conflict("STATE_CONFLICT");
        var after = new SpacePublicationPolicy(spaceId, policy, Math.incrementExact(expectedVersion));
        audit.successWithReason(AuditScope.SPACE_PUBLICATION_POLICY, requestId, actor, spaceId, "publication-policy",
                state(before), state(after), "POLICY_CHANGED");
        receipts.complete(binding, after);
        return after;
    }

    private static String state(SpacePublicationPolicy policy) {
        return "{\"publicationPolicy\":\"" + policy.policy().name() + "\",\"version\":" + policy.version() + "}";
    }
}
