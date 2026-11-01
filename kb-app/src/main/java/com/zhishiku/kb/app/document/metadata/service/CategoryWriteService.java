package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.model.CategoryRow;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.CATEGORY_UNAVAILABLE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NAME_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.REFERENCE_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.SPACE_ARCHIVED;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TREE_STRUCTURE_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TREE_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.UNAVAILABLE;

@Service
public class CategoryWriteService {
    private final CategoryMapper mapper;
    private final MetadataMapper metadata;
    private final MetadataAccess access;
    private final MetadataCommands commands;
    private final IdentityAuditService audit;

    public CategoryWriteService(CategoryMapper mapper, MetadataMapper metadata, MetadataAccess access,
                                MetadataCommands commands, IdentityAuditService audit) {
        this.mapper = mapper;
        this.metadata = metadata;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MetadataViews.CategoryReceipt write(long spaceId, Long id, MetadataInputs.CategoryWrite input, String requestId) {
        var initial = access.manager(spaceId);
        AuditScope scope = id == null ? AuditScope.DOCUMENT_CATEGORY_CREATE : AuditScope.DOCUMENT_CATEGORY_UPDATE;
        var binding = commands.bind(scope, requestId, initial.actorId(), id, spaceId, null, input.expectedTreeVersion(), input);
        var previous = commands.lock(binding);
        Long version = mapper.lockTree(spaceId);
        var actor = access.manager(spaceId);
        sameActor(initial.actorId(), actor.actorId());
        if (previous != null) {
            var receipt = commands.receipt(previous, MetadataViews.CategoryReceipt.class);
            return new MetadataViews.CategoryReceipt(receipt.categoryId(), receipt.treeVersion(), true);
        }
        writable(actor, version, input.expectedTreeVersion());
        CategoryRow before = id == null ? null : requireCategory(spaceId, id);
        List<CategoryRow> ancestors = input.parentId() == null ? List.of() : mapper.path(spaceId, input.parentId());
        if (input.parentId() != null && ancestors.isEmpty()) {
            throw new MetadataException(CATEGORY_UNAVAILABLE);
        }
        int height = before == null ? 1 : mapper.height(spaceId, id);
        if (height < 1 || ancestors.size() + height > 8
                || (!ancestors.isEmpty() && ancestors.getFirst().parentId() != null)
                || (id != null && ancestors.stream().anyMatch(node -> node.id() == id))) {
            throw new MetadataException(TREE_STRUCTURE_CONFLICT);
        }
        if (mapper.duplicate(spaceId, input.parentId(), input.normalizedName(), id == null ? 0 : id)) {
            throw new MetadataException(NAME_CONFLICT);
        }
        if (before != null && before.name().equals(input.name()) && Objects.equals(before.parentId(), input.parentId())
                && before.sortOrder() == input.sortOrder()) {
            throw new MetadataException(NO_CHANGE);
        }
        long target = id == null ? mapper.nextId() : id;
        var after = new CategoryRow(target, spaceId, input.parentId(), input.name(), input.normalizedName(), input.sortOrder(), null, null);
        int changed = id == null ? mapper.insert(after) : mapper.update(after);
        if (changed != 1 || mapper.advance(spaceId, version) != 1) {
            throw new MetadataException(UNAVAILABLE);
        }
        var receipt = new MetadataViews.CategoryReceipt(target, Math.addExact(version, 1), false);
        commands.save(binding, receipt);
        boolean nameChanged = before == null || !before.name().equals(after.name());
        audit.appendSuccess(scope, binding.requestId(), actor.actorId(), target, id == null ? "create" : "update",
                commands.write(state(before, version, false)), commands.write(state(after, receipt.treeVersion(), nameChanged)));
        return receipt;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MetadataViews.CategoryReceipt delete(long spaceId, long id, long expectedVersion, String requestId) {
        var initial = access.manager(spaceId);
        var binding = commands.bind(AuditScope.DOCUMENT_CATEGORY_DELETE, requestId, initial.actorId(), id, spaceId,
                null, expectedVersion, expectedVersion);
        var previous = commands.lock(binding);
        Long version = mapper.lockTree(spaceId);
        var actor = access.manager(spaceId);
        sameActor(initial.actorId(), actor.actorId());
        if (previous != null) {
            var receipt = commands.receipt(previous, MetadataViews.CategoryReceipt.class);
            return new MetadataViews.CategoryReceipt(id, receipt.treeVersion(), true);
        }
        writable(actor, version, expectedVersion);
        var before = requireCategory(spaceId, id);
        if (mapper.referenced(spaceId, id)) {
            throw new MetadataException(REFERENCE_CONFLICT);
        }
        if (mapper.delete(spaceId, id) != 1 || mapper.advance(spaceId, version) != 1) {
            throw new MetadataException(UNAVAILABLE);
        }
        var receipt = new MetadataViews.CategoryReceipt(id, Math.addExact(version, 1), false);
        commands.save(binding, receipt);
        audit.appendSuccess(binding.scope(), binding.requestId(), actor.actorId(), id, "delete",
                commands.write(state(before, version, false)), commands.write(state(before, receipt.treeVersion(), false)));
        return receipt;
    }

    private CategoryRow requireCategory(long spaceId, long id) {
        CategoryRow row = metadata.category(spaceId, id);
        if (row == null) {
            throw new MetadataException(CATEGORY_UNAVAILABLE);
        }
        return row;
    }

    private static void writable(MetadataAccess.Managed actor, Long version, long expected) {
        if (Boolean.TRUE.equals(actor.space().getArchived())) {
            throw new MetadataException(SPACE_ARCHIVED);
        }
        if (version == null) {
            throw new MetadataException(UNAVAILABLE);
        }
        if (version != expected) {
            throw new MetadataException(TREE_VERSION_CONFLICT);
        }
    }

    private static void sameActor(long expected, long current) {
        if (expected != current) {
            throw new MetadataException(FORBIDDEN);
        }
    }

    private static Object state(CategoryRow row, long version, boolean nameChanged) {
        if (row == null) {
            return java.util.Map.of("treeVersion", version);
        }
        return new CategoryState(row.spaceId(), row.id(), row.parentId(), row.sortOrder(), version, nameChanged);
    }

    private record CategoryState(long spaceId, long categoryId, Long parentId, int sortOrder, long treeVersion, boolean nameChanged) {
    }
}
