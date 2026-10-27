package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档详情，包含版本历史。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(name = "DocumentDetail", description = "文档详情和版本列表")
public class DocumentDetail extends DocumentSummary {

    @Schema(description = "版本历史，按版本号倒序")
    private List<DocumentVersionView> versions = List.of();

    public DocumentDetail(DocumentSummary summary, List<DocumentVersionView> versions) {
        super(summary.getId(), summary.getSpaceId(), summary.getTitle(), summary.getSecurityLevel(),
                summary.getStatus(), summary.getOwnerId(), summary.getCurrentVersionId(),
                summary.getCurrentVersionNo(), summary.getCurrentFileName(), summary.getCurrentFileSize(),
                summary.getCurrentMimeType(), summary.getCurrentIndexStatus(), summary.getCurrentIndexError(),
                summary.getCreatedAt(), summary.getUpdatedAt());
        this.versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
