package com.zhishiku.kb.app.document.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 文档分页结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "DocumentPage", description = "文档分页结果")
public class DocumentPage {
    @Schema(description = "当前页，从 1 开始", example = "1")
    private int page;
    @Schema(description = "每页条数", example = "20")
    private int size;
    @Schema(description = "总条数", example = "42")
    private long total;
    @Schema(description = "文档列表")
    private List<DocumentSummary> items;
}
