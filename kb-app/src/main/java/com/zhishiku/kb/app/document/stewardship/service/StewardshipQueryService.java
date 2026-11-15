package com.zhishiku.kb.app.document.stewardship.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityDeclaration;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityState;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityViews;
import com.zhishiku.kb.app.document.applicability.service.BusinessDate;
import com.zhishiku.kb.app.document.applicability.service.ValidityAccess;
import com.zhishiku.kb.app.document.applicability.service.ValidityCursor;
import com.zhishiku.kb.app.document.applicability.service.ValidityException;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.stewardship.mapper.StewardshipMapper;
import com.zhishiku.kb.app.document.stewardship.model.StewardCandidate;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipListRow;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipState;
import com.zhishiku.kb.app.document.stewardship.model.StewardshipViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class StewardshipQueryService {
    private final StewardshipMapper mapper;
    private final StewardshipAccess management;
    private final ValidityAccess access;
    private final PermissionService permissions;
    private final BusinessDate date;

    public StewardshipQueryService(StewardshipMapper mapper, StewardshipAccess management, ValidityAccess access,
                                   PermissionService permissions, BusinessDate date) {
        this.mapper = mapper;
        this.management = management;
        this.access = access;
        this.permissions = permissions;
        this.date = date;
    }

    @Transactional(readOnly = true, timeout = 3)
    public StewardshipViews.Detail get(long actor, long document) {
        var target = management.manager(actor, document, false);
        var row = mapper.get(document);
        var today = date.today();
        if (row == null) {
            return new StewardshipViews.Detail(document, target.spaceId(), StewardshipState.UNASSIGNED, null,
                    null, null, null, null, 0, today);
        }
        StewardCandidate responsible = row.responsibleUserId() == null ? null : mapper.responsible(document);
        if (row.responsibleUserId() != null && (responsible == null || responsible.userId() != row.responsibleUserId())) {
            throw new ValidityException(UNAVAILABLE);
        }
        boolean eligible = row.responsibleUserId() != null && mapper.eligible(document, row.responsibleUserId());
        return new StewardshipViews.Detail(document, target.spaceId(), StewardshipState.of(row.responsibleUserId(), eligible, row.dueDate(), today),
                responsible, row.cycleMonths(), row.firstAssignedOn(), row.lastConfirmedOn(), row.dueDate(), row.stewardshipVersion(), today);
    }

    @Transactional(readOnly = true, timeout = 3)
    public StewardshipViews.Mine mine(long actor, String view, int limit, String token) {
        if (!Set.of("attention", "all").contains(view) || limit < 1 || limit > 100) {
            throw ValidityInput.invalid();
        }
        String context = ValidityCursor.context("stewardship-mine", actor, actor, view);
        var cursor = ValidityCursor.decode(token, context);
        if (cursor != null && (cursor.date() == null || cursor.time() != null)) {
            throw ValidityInput.invalid();
        }
        var handle = access.current(actor);
        var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
        var today = date.today();
        var rows = mapper.mine(actor, view, today, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel(),
                cursor == null ? null : cursor.date(), cursor == null ? null : cursor.id(), limit + 1);
        if (rows == null || rows.size() > limit + 1) {
            throw new ValidityException(UNAVAILABLE);
        }
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        var items = page.stream().map(row -> mineItem(actor, row, today)).toList();
        if ("attention".equals(view) && items.stream().anyMatch(item -> item.attentionReasons().isEmpty())) {
            throw new ValidityException(UNAVAILABLE);
        }
        String next = rows.size() > limit ? new ValidityCursor(context, page.getLast().documentId(), page.getLast().dueDate(), null).encode() : null;
        return new StewardshipViews.Mine(items, next, today);
    }

    @Transactional(readOnly = true, timeout = 3)
    public StewardshipViews.Count count(long actor) {
        var handle = access.current(actor);
        var scope = permissions.resolve(handle, PermissionService.PERM_VIEW);
        var today = date.today();
        String count = mapper.count(actor, "attention", today, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel());
        if (count == null || !count.matches("0|[1-9][0-9]{0,18}")) {
            throw new ValidityException(UNAVAILABLE);
        }
        return new StewardshipViews.Count(count, today);
    }

    @Transactional(readOnly = true, timeout = 3)
    public StewardshipViews.Space space(long actor, long space, String state, int limit, String token) {
        if (!Set.of("UNASSIGNED", "NEEDS_REASSIGN", "DUE_SOON", "OVERDUE").contains(state) || limit < 1 || limit > 100) {
            throw ValidityInput.invalid();
        }
        boolean orderedByDate = "DUE_SOON".equals(state) || "OVERDUE".equals(state);
        String context = ValidityCursor.context("stewardship-space", actor, space, state);
        var cursor = ValidityCursor.decode(token, context);
        if (cursor != null && ((cursor.date() != null) != orderedByDate || cursor.time() != null)) {
            throw ValidityInput.invalid();
        }
        management.space(actor, space);
        var today = date.today();
        var rows = mapper.space(space, state, today, cursor == null ? null : cursor.date(), cursor == null ? null : cursor.id(), limit + 1);
        if (rows == null || rows.size() > limit + 1) {
            throw new ValidityException(UNAVAILABLE);
        }
        var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        var items = page.stream().map(row -> {
            var computed = StewardshipState.of(row.responsibleUserId(), row.eligible(), row.dueDate(), today);
            if (!computed.name().equals(state)) {
                throw new ValidityException(UNAVAILABLE);
            }
            return new StewardshipViews.SpaceItem(row.documentId(), computed, row.dueDate(), row.responsibleUserId() == null ? null
                    : new StewardCandidate(row.responsibleUserId(), row.displayName()));
        }).toList();
        String next = rows.size() > limit ? new ValidityCursor(context, page.getLast().documentId(), orderedByDate ? page.getLast().dueDate() : null, null).encode() : null;
        return new StewardshipViews.Space(items, next, today);
    }

    private static StewardshipViews.MineItem mineItem(long actor, StewardshipListRow row, LocalDate today) {
        if (!row.eligible() || row.responsibleUserId() == null || row.responsibleUserId() != actor || row.title() == null
                || row.cycleMonths() == null || row.dueDate() == null) {
            throw new ValidityException(UNAVAILABLE);
        }
        var state = StewardshipState.of(row.responsibleUserId(), true, row.dueDate(), today);
        var reasons = new ArrayList<String>();
        if (state == StewardshipState.DUE_SOON || state == StewardshipState.OVERDUE) {
            reasons.add(state == StewardshipState.DUE_SOON ? "REVIEW_DUE_SOON" : "REVIEW_OVERDUE");
        }
        ApplicabilityViews.Badge badge = null;
        if (row.versionId() != null) {
            var applicability = ApplicabilityState.of(new ApplicabilityDeclaration(row.effectiveFrom(), row.untilKind(), row.effectiveUntil()), today);
            badge = new ApplicabilityViews.Badge(row.documentId(), row.versionId(), applicability, row.effectiveFrom(), row.untilKind(), row.effectiveUntil(), row.corrected(), true);
            if (applicability == ApplicabilityState.EXPIRING_SOON || applicability == ApplicabilityState.EXPIRED) {
                reasons.add(applicability == ApplicabilityState.EXPIRING_SOON ? "APPLICABILITY_EXPIRING_SOON" : "APPLICABILITY_EXPIRED");
            }
        }
        return new StewardshipViews.MineItem(row.documentId(), row.title(), state, row.dueDate(), row.lastConfirmedOn(), row.cycleMonths(),
                row.stewardshipVersion(), List.copyOf(reasons), badge);
    }
}
