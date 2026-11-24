package com.zhishiku.kb.app.search.term;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 首次维护的最小回执，不是当前实体内容或版本。
 * @param kind 实体种类
 * @param id 稳定实体身份，JSON 十进制字符串
 * @param version 首次成功实体版本，JSON 十进制字符串
 * @param dictionaryRevision 首次成功词典修订，JSON 十进制字符串
 * @param replayed 是否匹配重放首次回执
 */
@Schema(description = "词典首次成功维护回执")
public record TermReceipt(
        @Schema(description = "实体种类") TermRows.EntityKind kind,
        @Schema(description = "稳定实体身份", type = "string") @JsonSerialize(using = ToStringSerializer.class) long id,
        @Schema(description = "首次实体版本", type = "string") @JsonSerialize(using = ToStringSerializer.class) long version,
        @Schema(description = "首次词典修订", type = "string") @JsonSerialize(using = ToStringSerializer.class) long dictionaryRevision,
        @Schema(description = "匹配重放标记") boolean replayed) {

    public TermReceipt asReplay() {
        return new TermReceipt(kind, id, version, dictionaryRevision, true);
    }
}
