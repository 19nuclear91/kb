package com.zhishiku.kb.app.space.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.zhishiku.kb.common.review.PublicationPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 空间策略及其乐观锁版本，不代表阅读或审核资格。
 *
 * @param spaceId 精确空间标识
 * @param policy 仅影响之后创建版本的策略
 * @param version 当前空间版本
 */
@Schema(description = "空间发布策略")
public record SpacePublicationPolicy(
        @Schema(description = "空间标识，十进制字符串") @JsonSerialize(using = ToStringSerializer.class) long spaceId,
        @Schema(description = "新版本发布策略") PublicationPolicy policy,
        @Schema(description = "空间并发版本，十进制字符串") @JsonSerialize(using = ToStringSerializer.class) long version) {
}
