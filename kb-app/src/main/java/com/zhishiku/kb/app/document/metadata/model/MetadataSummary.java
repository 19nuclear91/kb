package com.zhishiku.kb.app.document.metadata.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zhishiku.kb.app.document.model.DocumentSummary;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class MetadataSummary extends DocumentSummary {
    @JsonIgnore
    private Long categoryId;
    private long metadataVersion;
    private MetadataViews.Category category;
    private List<MetadataViews.Tag> tags = List.of();
}
