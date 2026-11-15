package com.zhishiku.kb.app.document.applicability.service;

import com.zhishiku.kb.app.auth.service.PermissionService;
import com.zhishiku.kb.app.document.applicability.mapper.ApplicabilityMapper;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityState;
import com.zhishiku.kb.app.document.applicability.model.ApplicabilityViews;
import com.zhishiku.kb.app.document.applicability.support.ValidityInput;
import com.zhishiku.kb.app.document.service.DocumentVersionAccessService;
import com.zhishiku.kb.common.conversation.SourceKey;
import com.zhishiku.kb.common.review.VersionAccessPurpose;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.applicability.service.ValidityException.Reason.UNAVAILABLE;

@Service
public class ApplicabilityReadService {
    private final ApplicabilityMapper mapper;
    private final ValidityAccess access;
    private final DocumentVersionAccessService versions;
    private final BusinessDate date;

    public ApplicabilityReadService(ApplicabilityMapper mapper, ValidityAccess access,
                                    DocumentVersionAccessService versions, BusinessDate date) {
        this.mapper = mapper;
        this.access = access;
        this.versions = versions;
        this.date = date;
    }

    @Transactional(readOnly = true, timeout = 3)
    public ApplicabilityViews.Detail get(long actor, long document, long version) {
        var handle = access.current(actor);
        access.version(handle, document, version);
        var target = mapper.target(document, version);
        var row = mapper.get(document, version);
        if (target == null || row == null) {
            throw new ValidityException(NOT_FOUND);
        }
        var today = date.today();
        boolean frozen = !target.visibleToReaders() && target.reviewInProgress();
        boolean editable = !frozen && !target.spaceArchived() && !"archived".equals(target.documentStatus())
                && access.has(handle, document, PermissionService.PERM_EDIT);
        return new ApplicabilityViews.Detail(document, version, row.effectiveFrom(), row.untilKind(), row.effectiveUntil(),
                ApplicabilityState.of(row.declaration(), today), row.corrected(), row.applicabilityVersion(), today, editable, frozen,
                target.visibleToReaders());
    }

    @Transactional(readOnly = true, timeout = 3)
    public ApplicabilityViews.History history(long actor, long document, long version, int limit, String token) {
        if (limit < 1 || limit > 100) {
            throw ValidityInput.invalid();
        }
        String context = ValidityCursor.context("applicability-history", actor, document, Long.toString(version));
        var cursor = ValidityCursor.decode(token, context);
        if (cursor != null && (cursor.time() == null || cursor.date() != null)) {
            throw ValidityInput.invalid();
        }
        access.version(access.current(actor), document, version);
        var rows = mapper.history(document, version, cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(), limit + 1);
        if (rows == null || rows.size() > limit + 1) {
            throw new ValidityException(UNAVAILABLE);
        }
        var page = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = null;
        if (rows.size() > limit) {
            var last = page.getLast();
            next = new ValidityCursor(context, last.id(), null, last.changedAt()).encode();
        }
        return new ApplicabilityViews.History(page, next, date.today());
    }

    @Transactional(readOnly = true, timeout = 3)
    public ApplicabilityViews.Batch batch(long actor, List<SourceKey> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 100 || sources.stream().anyMatch(key -> key == null
                || key.documentId() <= 0 || key.versionId() <= 0) || new HashSet<>(sources).size() != sources.size()) {
            throw ValidityInput.invalid();
        }
        var handle = access.current(actor);
        Set<SourceKey> visible;
        try {
            visible = versions.visible(handle, Set.copyOf(sources), VersionAccessPurpose.INTERACTIVE);
            if (visible == null || !sources.containsAll(visible)) {
                throw new ValidityException(UNAVAILABLE);
            }
        } catch (RuntimeException ignored) {
            throw new ValidityException(UNAVAILABLE);
        }
        var today = date.today();
        if (visible.isEmpty()) {
            return new ApplicabilityViews.Batch(List.of(), today);
        }
        try {
            var rows = mapper.batch(sources.stream().filter(visible::contains).toList());
            var returned = new HashSet<SourceKey>();
            if (rows == null || rows.stream().anyMatch(row -> !visible.contains(new SourceKey(row.documentId(), row.versionId()))
                    || !returned.add(new SourceKey(row.documentId(), row.versionId())))) {
                throw new ValidityException(UNAVAILABLE);
            }
            return new ApplicabilityViews.Batch(rows.stream().map(row -> ApplicabilityViews.Badge.of(row, today)).toList(), today);
        } catch (RuntimeException ignored) {
            throw new ValidityException(UNAVAILABLE);
        }
    }
}
