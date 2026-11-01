package com.zhishiku.kb.app.search.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;

/**
 * 与授权范围独立的不可变文档筛选条件。
 *
 * @param spaceId       所选空间，null 表示不限
 * @param categoryId    所选分类及其后代，null 表示不限
 * @param uncategorized 是否仅匹配未分类文档
 * @param tagIds        必须全部关联的标签 ID
 * @param fileType      当前发布版本文件类型，null 表示不限
 * @param updatedFrom   文档更新时间起点，包含端点
 * @param updatedTo     文档更新时间终点，不包含端点
 */
@Schema(description = "与服务端授权范围独立的文档组合筛选")
public record SearchFilters(
        @Schema(description = "所选空间 ID") Long spaceId,
        @Schema(description = "所选分类 ID，包含后代") Long categoryId,
        @Schema(description = "仅匹配未分类文档") boolean uncategorized,
        @Schema(description = "至多 20 个不重复标签 ID，全部匹配") List<Long> tagIds,
        @Schema(description = "当前发布版本文件类型") FileType fileType,
        @Schema(description = "更新时间含起点") OffsetDateTime updatedFrom,
        @Schema(description = "更新时间不含终点") OffsetDateTime updatedTo) {

    private static final SearchFilters EMPTY = new SearchFilters(null, null, false, List.of(), null, null, null);

    public SearchFilters {
        if ((spaceId != null && spaceId <= 0) || (categoryId != null && categoryId <= 0)
                || (categoryId != null && uncategorized) || tagIds == null || tagIds.size() > 20) {
            throw invalid();
        }
        var seen = new HashSet<Long>();
        for (Long id : tagIds) {
            if (id == null || id <= 0 || !seen.add(id)) {
                throw invalid();
            }
        }
        tagIds = tagIds.stream().sorted().toList();
        if (updatedFrom != null && updatedTo != null && !updatedFrom.isBefore(updatedTo)) {
            throw invalid();
        }
    }

    public static SearchFilters empty() {
        return EMPTY;
    }

    public boolean restricted() {
        return spaceId != null || categoryId != null || uncategorized || !tagIds.isEmpty()
                || fileType != null || updatedFrom != null || updatedTo != null;
    }

    private static SearchFailure invalid() {
        return new SearchFailure(SearchFailure.Kind.INVALID_INPUT);
    }

    @Override
    public String toString() {
        return "SearchFilters[restricted=" + restricted() + "]";
    }

    /** 可参与全文检索的已发布版本类型。 */
    @Schema(description = "已发布文件类型，不包含未发布候选")
    public enum FileType {
        PDF, TXT, MARKDOWN, WORD, EXCEL, POWERPOINT, OTHER
    }
}
