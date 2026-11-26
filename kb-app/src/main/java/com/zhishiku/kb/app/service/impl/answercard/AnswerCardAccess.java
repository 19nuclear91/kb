package com.zhishiku.kb.app.service.impl.answercard;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceFact;
import com.zhishiku.kb.app.domain.dto.answercard.AnswerCardSourceKey;
import com.zhishiku.kb.app.mapper.answercard.AnswerCardSourceMapper;
import com.zhishiku.kb.app.service.answercard.AnswerCardFailure;
import com.zhishiku.kb.app.space.model.SpaceRecord;
import com.zhishiku.kb.app.space.service.SpaceQueryService;
import com.zhishiku.kb.common.security.PermissionScope;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 仅在当前调用栈解析身份和来源；管理资格与正文资格分别执行。 */
@Service
public class AnswerCardAccess {
    private final PermissionService permissions;
    private final SpaceQueryService spaces;
    private final AnswerCardSourceMapper sources;
    private final SqlSessionTemplate sessions;

    public AnswerCardAccess(PermissionService permissions, SpaceQueryService spaces,
                            AnswerCardSourceMapper sources, SqlSessionTemplate sessions) {
        this.permissions = permissions;
        this.spaces = spaces;
        this.sources = sources;
        this.sessions = sessions;
    }

    public PermissionService.PrincipalHandle current(long actor) {
        sessions.clearCache();
        var handle = permissions.principalOf(actor);
        handle.requireEnabled();
        return handle;
    }

    public void requireFunction(PermissionService.PrincipalHandle actor, Permission permission) {
        actor.requireEnabled();
        if (!actor.functionalPermissions().contains(permission.code())) {
            throw AnswerCardFailure.forbidden();
        }
    }

    public PermissionScope view(PermissionService.PrincipalHandle actor) {
        actor.requireEnabled();
        return permissions.resolve(actor, PermissionService.PERM_VIEW);
    }

    public boolean space(PermissionService.PrincipalHandle actor, long spaceId, String permission) {
        actor.requireEnabled();
        return permissions.resolve(actor, permission).visibleSpaceIdList().contains(spaceId);
    }

    public void requireWriter(PermissionService.PrincipalHandle actor, long spaceId) {
        requireFunction(actor, Permission.ANSWER_CARD_WRITE);
        if (!space(actor, spaceId, PermissionService.PERM_VIEW)
                || !space(actor, spaceId, PermissionService.PERM_EDIT)) {
            throw AnswerCardFailure.hidden();
        }
    }

    public void requireReviewer(PermissionService.PrincipalHandle actor, long spaceId) {
        requireFunction(actor, Permission.ANSWER_CARD_REVIEW);
        if (!space(actor, spaceId, PermissionService.PERM_VIEW)
                || !space(actor, spaceId, PermissionService.PERM_REVIEW)) {
            throw AnswerCardFailure.hidden();
        }
    }

    public void requireContentOperator(PermissionService.PrincipalHandle actor, long spaceId) {
        boolean writer = actor.functionalPermissions().contains(Permission.ANSWER_CARD_WRITE.code());
        boolean reviewer = actor.functionalPermissions().contains(Permission.ANSWER_CARD_REVIEW.code());
        if (!writer && !reviewer) {
            throw AnswerCardFailure.forbidden();
        }
        if (!space(actor, spaceId, PermissionService.PERM_VIEW)
                || !(writer && space(actor, spaceId, PermissionService.PERM_EDIT)
                || reviewer && space(actor, spaceId, PermissionService.PERM_REVIEW))) {
            throw AnswerCardFailure.hidden();
        }
    }

    public SpaceRecord requireManager(PermissionService.PrincipalHandle actor, long spaceId, boolean writing) {
        var space = spaces.findManaged(actor, spaceId);
        if (space == null) {
            throw AnswerCardFailure.hidden();
        }
        if (writing && Boolean.TRUE.equals(space.getArchived())) {
            throw AnswerCardFailure.conflict("SPACE_ARCHIVED");
        }
        return space;
    }

    public List<AnswerCardSourceFact> requireSources(PermissionService.PrincipalHandle actor,
                                                    List<AnswerCardSourceKey> keys, boolean requireCurrent) {
        if (keys == null || keys.size() > 30) {
            throw AnswerCardFailure.invalid();
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        var unique = new HashSet<String>();
        for (var key : keys) {
            if (key == null || key.documentId() <= 0 || key.versionId() <= 0 || key.applicabilityVersion() < 0
                    || !unique.add(key.documentId() + ":" + key.versionId())) {
                throw AnswerCardFailure.invalid();
            }
        }
        var scope = view(actor);
        var facts = sources.facts(keys, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()),
                scope.clearanceLevel());
        if (facts == null) {
            throw AnswerCardFailure.unavailable();
        }
        for (var key : keys) {
            var fact = facts.stream().filter(value -> value.documentId() == key.documentId()
                    && value.versionId() == key.versionId()).findFirst().orElseThrow(AnswerCardFailure::hidden);
            if (requireCurrent && (!Objects.equals(fact.currentVersionId(), key.versionId())
                    || fact.applicabilityVersion() != key.applicabilityVersion())) {
                throw AnswerCardFailure.conflict("SOURCE_CHANGED");
            }
        }
        if (facts.size() != keys.size()) {
            throw AnswerCardFailure.unavailable();
        }
        return List.copyOf(facts);
    }
}
