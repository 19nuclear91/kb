package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.app.search.model.SearchFilters;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 只接受检索条件；调用者身份和向量始终由服务端决定。
 *
 * @param query      授权检索问题
 * @param candidates 每路召回候选数
 * @param topK       重排后返回数量
 * @param spaceId 所选空间
 * @param categoryId 所选分类及后代
 * @param uncategorized 是否只检索未分类文档
 * @param tagIds 必须全部匹配的标签
 * @param fileType 当前发布文件类型
 * @param updatedFrom 文档更新时间含起点
 * @param updatedTo 文档更新时间不含终点
 */
@JsonDeserialize(using = SearchRequestDeserializer.class)
@Schema(description = "授权混合检索请求，所有条件按 AND 组合")
public record SearchRequest(
        @Schema(description = "检索问题", example = "如何拆分微服务？") @NotBlank String query,
        @Schema(description = "每路召回候选数，默认 50，范围 1-200", example = "50")
        @Min(1) @Max(200) Integer candidates,
        @Schema(description = "重排后返回数量，默认等于 candidates，范围 1-200", example = "8")
        @Min(1) @Max(200) Integer topK,
        @Schema(description = "空间 ID，可空") Long spaceId,
        @Schema(description = "分类 ID，包含后代，可空") Long categoryId,
        @Schema(description = "仅未分类，与 categoryId 互斥") boolean uncategorized,
        @Schema(description = "最多 20 个标签，全部匹配") List<Long> tagIds,
        @Schema(description = "当前发布文件类型") SearchFilters.FileType fileType,
        @Schema(description = "更新时间含起点，带时区") OffsetDateTime updatedFrom,
        @Schema(description = "更新时间不含终点，带时区") OffsetDateTime updatedTo) {

    public SearchRequest {
        tagIds = new SearchFilters(spaceId, categoryId, uncategorized, tagIds, fileType, updatedFrom, updatedTo).tagIds();
    }

    public SearchRequest(String query, Integer candidates, Integer topK) {
        this(query, candidates, topK, null, null, false, List.of(), null, null, null);
    }

    public SearchFilters filters() {
        return new SearchFilters(spaceId, categoryId, uncategorized, tagIds, fileType, updatedFrom, updatedTo);
    }

    @Override
    public String toString() {
        return "SearchRequest";
    }
}
