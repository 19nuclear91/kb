package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 对外返回的部门摘要。
 *
 * <p>{@code path} 是服务端权限解析所需的祖先链信息，调用方不得据此自行推导授权结果。
 *
 * @param departmentId 部门内部 ID
 * @param code         稳定部门编码
 * @param name         部门名称
 * @param parentId     父部门内部 ID；根部门为空
 * @param path         从根部门到当前部门的祖先路径
 * @param enabled      部门是否启用
 * @param version      当前部门版本，用于并发更新
 */
@Schema(name = "DepartmentSummary", description = "部门身份摘要")
public record DepartmentSummary(
        @Schema(description = "部门内部 ID", example = "10")
        long departmentId,
        @Schema(description = "稳定部门编码", example = "platform")
        String code,
        @Schema(description = "部门名称", example = "平台组")
        String name,
        @Schema(description = "父部门内部 ID；根部门为空", example = "1")
        Long parentId,
        @Schema(description = "从根部门到当前部门的祖先路径", example = "/1/10/")
        String path,
        @Schema(description = "部门是否启用", example = "true")
        boolean enabled,
        @Schema(description = "当前部门版本，用于并发更新", example = "3")
        long version) {
}
