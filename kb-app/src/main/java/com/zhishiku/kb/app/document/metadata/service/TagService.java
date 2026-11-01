package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.metadata.model.TagRow;
import com.zhishiku.kb.app.document.metadata.web.MetadataInput;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NAME_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TAG_UNAVAILABLE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TAG_VERSION_CONFLICT;

@Service
public class TagService {
    private final TagMapper mapper;
    private final MetadataAccess access;
    private final MetadataCommands commands;
    private final IdentityAuditService audit;

    public TagService(TagMapper mapper, MetadataAccess access, MetadataCommands commands, IdentityAuditService audit) {
        this.mapper = mapper;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MetadataViews.TagReceipt write(Long id, MetadataInputs.TagWrite input, String requestId) {
        long initial = access.tagManager(true);
        AuditScope scope = id == null ? AuditScope.DOCUMENT_TAG_CREATE : AuditScope.DOCUMENT_TAG_UPDATE;
        var binding = commands.bind(scope, requestId, initial, id, null, null, input.expectedVersion(), input);
        var previous = commands.lock(binding);
        TagRow before = id == null ? null : mapper.lock(id);
        long actor = access.tagManager(true);
        if (actor != initial) {
            throw new MetadataException(FORBIDDEN);
        }
        if (previous != null) {
            var receipt = commands.receipt(previous, MetadataViews.TagReceipt.class);
            return new MetadataViews.TagReceipt(receipt.tagId(), receipt.version(), true);
        }
        if (id != null && before == null) {
            throw new MetadataException(TAG_UNAVAILABLE);
        }
        if (before != null && !Long.valueOf(before.version()).equals(input.expectedVersion())) {
            throw new MetadataException(TAG_VERSION_CONFLICT);
        }
        if (before != null && before.name().equals(input.name()) && before.enabled() == input.enabled()) {
            throw new MetadataException(NO_CHANGE);
        }
        long target = id == null ? mapper.nextId() : id;
        long version = before == null ? 0 : before.version();
        TagRow after = new TagRow(target, input.name(), input.normalizedName(), input.enabled(), version, null, null);
        try {
            if (id == null && mapper.insert(after) != 1) {
                throw new MetadataException(NAME_CONFLICT);
            }
            if (id != null && mapper.update(after) != 1) {
                throw new MetadataException(TAG_VERSION_CONFLICT);
            }
        } catch (DuplicateKeyException ignored) {
            throw new MetadataException(NAME_CONFLICT);
        }
        var receipt = new MetadataViews.TagReceipt(target, before == null ? 0 : Math.addExact(version, 1), false);
        commands.save(binding, receipt);
        audit.appendSuccess(scope, binding.requestId(), actor, target, id == null ? "create" : "update",
                commands.write(before == null ? Map.of() : new TagState(target, before.enabled(), before.version(), false)),
                commands.write(new TagState(target, after.enabled(), receipt.version(), before == null || !before.name().equals(after.name()))));
        return receipt;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.Page<MetadataViews.ManagedTag> managed(MetadataInputs.TagQuery input) {
        access.tagManager(false);
        String context = context("tag-manager", null, input);
        var cursor = MetadataCursor.decode(input.cursor(), context, null, 0);
        var page = page(mapper.list(MetadataInput.literalPattern(input.nameQuery()), input.enabled(),
                cursor == null ? null : cursor.id(), input.limit() + 1), input.limit(), context, null);
        return new MetadataViews.Page<>(page.items().stream().map(row -> new MetadataViews.ManagedTag(row.id(), row.name(),
                row.enabled(), row.version(), row.createdAt(), row.updatedAt())).toList(), page.hasMore(), page.nextCursor());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.Page<MetadataViews.Tag> editable(long documentId, MetadataInputs.TagQuery input) {
        long space = access.document(documentId, true).document().getSpaceId();
        String context = context("tag-edit", documentId, input);
        var cursor = MetadataCursor.decode(input.cursor(), context, space, 0);
        return tags(page(mapper.list(MetadataInput.literalPattern(input.nameQuery()), true,
                cursor == null ? null : cursor.id(), input.limit() + 1), input.limit(), context, space));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public MetadataViews.Page<MetadataViews.Tag> filter(MetadataInputs.TagQuery input) {
        var scope = access.view();
        String context = context("tag-filter", null, input);
        var cursor = MetadataCursor.decode(input.cursor(), context, null, 0);
        return tags(page(mapper.visible(MetadataInput.literalPattern(input.nameQuery()), cursor == null ? null : cursor.id(),
                input.limit() + 1, scope.visibleSpaceIdList(), Arrays.asList(scope.principalKeys()), scope.clearanceLevel()),
                input.limit(), context, null));
    }

    private static String context(String purpose, Long resource, MetadataInputs.TagQuery input) {
        return MetadataCursor.context(purpose, resource, null, input.enabled() + ":"
                + (input.nameQuery() == null ? "absent" : "text:" + input.nameQuery()));
    }

    private static MetadataViews.Page<TagRow> page(List<TagRow> rows, int limit, String context, Long space) {
        var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
        boolean more = rows.size() > limit;
        String next = more ? new MetadataCursor(context, space, 0, 0, items.getLast().id()).encode() : null;
        return new MetadataViews.Page<>(items, more, next);
    }

    private static MetadataViews.Page<MetadataViews.Tag> tags(MetadataViews.Page<TagRow> page) {
        return new MetadataViews.Page<>(page.items().stream().map(row -> new MetadataViews.Tag(row.id(), row.name(), row.enabled())).toList(),
                page.hasMore(), page.nextCursor());
    }

    private record TagState(long tagId, boolean enabled, long version, boolean nameChanged) {
    }
}
