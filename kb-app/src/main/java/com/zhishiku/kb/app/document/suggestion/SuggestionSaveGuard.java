package com.zhishiku.kb.app.document.suggestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.app.document.model.DocumentEntity;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class SuggestionSaveGuard {
    private final SuggestionStore store;
    private final SuggestionAccess access;

    public SuggestionSaveGuard(SuggestionStore store, SuggestionAccess access) {
        this.store = store;
        this.access = access;
    }

    public record Adoption(UUID generation, List<String> fields) {
        public Adoption {
            var allowed = Set.of("title", "summary", "categoryId");
            if (generation == null || fields == null || fields.isEmpty() || fields.size() > 3
                    || !allowed.containsAll(fields) || new TreeSet<>(fields).size() != fields.size()) {
                throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
            }
            fields = fields.stream().sorted().toList();
        }

        public static Adoption parse(UUID generation, JsonNode node) {
            if (node == null || !node.isArray() || node.size() > 3) {
                throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
            }
            var names = new ArrayList<String>();
            for (JsonNode value : node) {
                if (!value.isTextual()) {
                    throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
                }
                names.add(value.textValue());
            }
            return new Adoption(generation, names);
        }
    }

    public void check(Adoption adoption, long actor, DocumentEntity doc, long tree, boolean replay) {
        var job = store.find(adoption.generation());
        if (job == null || job.docId() != doc.getId() || actor != job.actorId()) {
            throw new MetadataException(MetadataException.Reason.NOT_FOUND);
        }
        access.requireSource(actor, doc.getId(), job.sourceVersionId());
        if (replay) {
            return;
        }
        if (!Objects.equals(doc.getCurrentVersionId(), job.sourceVersionId())) {
            throw new MetadataException(MetadataException.Reason.SOURCE_CHANGED);
        }
        if (job.spaceId() != doc.getSpaceId() || job.metadataVersion() != doc.getMetadataVersion()) {
            throw new MetadataException(MetadataException.Reason.METADATA_VERSION_CONFLICT);
        }
        if (job.treeVersion() != tree) {
            throw new MetadataException(MetadataException.Reason.TREE_VERSION_CONFLICT);
        }
        var result = store.result(job.requestId(), OffsetDateTime.now(ZoneOffset.UTC));
        if (!"COMPLETED".equals(job.generationStatus()) || result == null) {
            throw new MetadataException(MetadataException.Reason.SUGGESTION_EXPIRED);
        }
        if (adoption.fields().contains("categoryId") && store.decode(result).category() == null) {
            throw new MetadataException(MetadataException.Reason.INVALID_INPUT);
        }
    }
}
