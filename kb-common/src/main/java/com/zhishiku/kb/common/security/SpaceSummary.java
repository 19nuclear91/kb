package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 空间摘要。
 *
 * <p>{@code viewable} 与 {@code manageable} 是<b>按当前调用者分别判定</b>的两个独立
 * 事实，不是空间自身的属性：同一空间对不同用户会得到不同取值。
 *
 * <p>两者之所以必须分开，是因为 manage 不蕴含 view（见
 * {@code SpaceAuthorizer} 的类注释）：存在"能改授权名单、读不到空间内容"的
 * 操作者。若把它们压成一个"可访问"布尔值，持有 manage 的操作者就会被顺带
 * 判成可读，那正是要避免的越权。
 *
 * @param spaceId     空间内部 ID
 * @param code        空间全局唯一编码
 * @param name        空间名称
 * @param description 空间描述
 * @param archived    空间是否已归档
 * @param version     当前空间版本，用于并发更新
 * @param viewable    当前用户是否可以读取空间内容
 * @param manageable  当前用户是否可以管理空间元数据和授权
 */
@Schema(name = "SpaceSummary", description = "按当前用户权限计算的空间摘要")
public record SpaceSummary(
        @Schema(description = "空间内部 ID", example = "1")
        long spaceId,
        @Schema(description = "空间全局唯一编码", example = "engineering")
        String code,
        @Schema(description = "空间名称", example = "工程知识库")
        String name,
        @Schema(description = "空间描述")
        String description,
        @Schema(description = "空间是否已归档", example = "false")
        boolean archived,
        @Schema(description = "当前空间版本，用于并发更新", example = "2")
        long version,
        @Schema(description = "当前用户是否可以读取空间内容", example = "true")
        boolean viewable,
        @Schema(description = "当前用户是否可以管理空间元数据和授权", example = "true")
        boolean manageable) {
}
