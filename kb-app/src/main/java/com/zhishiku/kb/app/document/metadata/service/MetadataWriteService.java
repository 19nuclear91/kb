package com.zhishiku.kb.app.document.metadata.service;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.document.metadata.mapper.CategoryMapper;
import com.zhishiku.kb.app.document.metadata.mapper.MetadataMapper;
import com.zhishiku.kb.app.document.metadata.mapper.TagMapper;
import com.zhishiku.kb.app.document.metadata.model.MetadataInputs;
import com.zhishiku.kb.app.document.metadata.model.MetadataViews;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.CATEGORY_UNAVAILABLE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.FORBIDDEN;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.METADATA_VERSION_CONFLICT;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NO_CHANGE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.NOT_FOUND;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.TAG_UNAVAILABLE;
import static com.zhishiku.kb.app.document.metadata.service.MetadataException.Reason.UNAVAILABLE;

@Service
public class MetadataWriteService {
    private final MetadataMapper mapper;
    private final CategoryMapper categories;
    private final TagMapper tags;
    private final MetadataAccess access;
    private final MetadataCommands commands;
    private final IdentityAuditService audit;

    public MetadataWriteService(MetadataMapper mapper, CategoryMapper categories, TagMapper tags, MetadataAccess access,
                                MetadataCommands commands, IdentityAuditService audit) {
        this.mapper = mapper;
        this.categories = categories;
        this.tags = tags;
        this.access = access;
        this.commands = commands;
        this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MetadataViews.Receipt update(long id, MetadataInputs.Update input, String requestId) {
        var initial = access.document(id, true);
        var binding = commands.bind(AuditScope.DOCUMENT_METADATA_UPDATE, requestId, initial.actorId(), id, null, id,
                input.expectedMetadataVersion(), input);
        var previous = commands.lock(binding);
        var hint = mapper.document(id);
        if (hint == null) {
            throw new MetadataException(NOT_FOUND);
        }
        if (categories.shareTree(hint.getSpaceId()) == null) {
            throw new MetadataException(METADATA_VERSION_CONFLICT);
        }
        var selected = tags.share(input.tagIds());
        DocumentEntity before = mapper.lockDocument(id);
        var current = access.document(id, true);
        if (initial.actorId() != current.actorId()) {
            throw new MetadataException(FORBIDDEN);
        }
        if (before == null || !Objects.equals(hint.getSpaceId(), before.getSpaceId())) {
            throw new MetadataException(METADATA_VERSION_CONFLICT);
        }
        if (previous != null) {
            var result = commands.receipt(previous, MetadataViews.Receipt.class);
            return new MetadataViews.Receipt(result.documentId(), result.metadataVersion(), result.updatedAt(), result.changedFields(), true);
        }
        if (before.getMetadataVersion() != input.expectedMetadataVersion()) {
            throw new MetadataException(METADATA_VERSION_CONFLICT);
        }
        if (input.categoryId() != null && mapper.category(before.getSpaceId(), input.categoryId()) == null) {
            throw new MetadataException(CATEGORY_UNAVAILABLE);
        }
        List<Long> originalTags = tags.forDocument(id).stream().map(tag -> tag.id()).toList();
        if (selected.size() != input.tagIds().size()
                || selected.stream().anyMatch(tag -> !tag.enabled() && !originalTags.contains(tag.id()))) {
            throw new MetadataException(TAG_UNAVAILABLE);
        }
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.getTitle(), input.title())) {
            changed.add("title");
        }
        if (!Objects.equals(before.getSummary(), input.summary())) {
            changed.add("summary");
        }
        if (!Objects.equals(before.getCategoryId(), input.categoryId())) {
            changed.add("categoryId");
        }
        if (!originalTags.equals(input.tagIds())) {
            changed.add("tagIds");
        }
        if (changed.isEmpty()) {
            throw new MetadataException(NO_CHANGE);
        }
        DocumentEntity after = DocumentEntity.builder().id(id).title(input.title()).summary(input.summary())
                .categoryId(input.categoryId()).metadataVersion(input.expectedMetadataVersion()).build();
        if (mapper.updateMetadata(after) != 1) {
            throw new MetadataException(METADATA_VERSION_CONFLICT);
        }
        if (changed.contains("tagIds")) {
            mapper.clearTags(id);
            if (!input.tagIds().isEmpty() && mapper.insertTags(id, input.tagIds()) != input.tagIds().size()) {
                throw new MetadataException(UNAVAILABLE);
            }
        }
        after = mapper.document(id);
        var receipt = new MetadataViews.Receipt(id, after.getMetadataVersion(), after.getUpdatedAt(), changed, false);
        commands.save(binding, receipt);
        audit.appendSuccess(binding.scope(), binding.requestId(), current.actorId(), id, "update",
                commands.write(new State(before.getCategoryId(), originalTags, before.getMetadataVersion(), List.of())),
                commands.write(new State(input.categoryId(), input.tagIds(), after.getMetadataVersion(), changed)));
        return receipt;
    }

    private record State(Long categoryId, List<Long> tagIds, long metadataVersion, List<String> changedFields) {
    }
}
