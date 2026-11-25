package com.zhishiku.kb.app.search.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.zhishiku.kb.app.search.expansion.SearchQueryPlan;
import com.zhishiku.kb.app.search.model.AssistedSearchInput;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 增强入口的平坦 JSON 契约；不扩大旧入口的字段集合。 */
@JsonDeserialize(using = AssistedSearchRequestDeserializer.class)
@Schema(description = "辅助检索平坦 JSON 请求，包含普通检索全部条件及辅助选择", allOf = SearchRequest.class)
public record AssistedSearchRequest(
        @Schema(hidden = true)
        SearchRequest base,
        @Schema(description = "ASSISTED 词典辅助，ORIGINAL 仅按原文检索；默认 ASSISTED", defaultValue = "ASSISTED")
        SearchQueryPlan.Mode mode,
        @Schema(description = "上次建议的上下文凭据；有人工选择时必填，无选择时省略", maxLength = 4096)
        String selectionContext,
        @ArraySchema(arraySchema = @Schema(description = "最多 8 项累计人工选择，原文区间不得重叠；默认空数组"), maxItems = 8, schema = @Schema(implementation = Selection.class))
        List<SearchQueryPlan.Selection> selections
) {
    public AssistedSearchRequest {
        if (base == null || mode == null || selections == null) {
            throw SearchJson.invalid();
        }
        int candidates = base.candidates() == null ? 50 : base.candidates();
        int topK = base.topK() == null ? candidates : base.topK();
        selections = new AssistedSearchInput(base.query(), candidates, topK, base.filters(), mode, selectionContext, selections).selections();
    }

    public int candidates() {
        return base.candidates() == null ? 50 : base.candidates();
    }

    public int topK() {
        return base.topK() == null ? candidates() : base.topK();
    }

    public void validate(int maxCodepoints) {
        toInput().validate(maxCodepoints);
    }

    public AssistedSearchInput toInput() {
        return new AssistedSearchInput(base.query(), candidates(), topK(), base.filters(), mode, selectionContext, selections);
    }

    @Schema(name = "AssistedSearchSelectionInput", description = "请求中的人工选择，所有 ID 和位置均为精确 JSON 整数")
    public record Selection(
            @Schema(description = "CORRECTION 纠错选择；DISAMBIGUATION 歧义含义选择", requiredMode = Schema.RequiredMode.REQUIRED)
            SearchQueryPlan.SelectionKind kind,
            @Schema(description = "原文 Unicode code point 起点，包含，最小 0", minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
            int start,
            @Schema(description = "原文 Unicode code point 终点，不包含，须大于起点且不超过原文长度", requiredMode = Schema.RequiredMode.REQUIRED)
            int end,
            @Schema(description = "选定词条 ID，1 至 9223372036854775807 的精确 JSON 整数", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
            long termId
    ) {
    }

    @Override
    public String toString() {
        return "AssistedSearchRequest[redacted]";
    }
}
