package com.zhishiku.kb.app.search.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 只接受检索条件；调用者身份和向量始终由服务端决定。
 *
 * @param query      授权检索问题
 * @param candidates 每路召回候选数
 * @param topK       重排后返回数量
 */
public record SearchRequest(
        @Schema(description = "检索问题", example = "如何拆分微服务？") @NotBlank String query,
        @Schema(description = "每路召回候选数，默认 50，范围 1-200", example = "50")
        @Min(1) @Max(200) Integer candidates,
        @Schema(description = "重排后返回数量，默认等于 candidates，范围 1-200", example = "8")
        @Min(1) @Max(200) Integer topK) {
}
