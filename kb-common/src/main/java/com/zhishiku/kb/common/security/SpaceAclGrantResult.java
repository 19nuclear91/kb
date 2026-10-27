package com.zhishiku.kb.common.security;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 空间授权的授予回执。
 *
 * <p>{@code clearanceBlockedPrincipalCount} 是核心字段：授权不修改密级，
 * 因此一次授权可能对部分主体当下无效（ACL 与密级是"与"关系）。
 * 用回执如实告知，而不是在响应里假装授权全部生效。
 *
 * <p>{@code alreadyGranted} 为真表示这条授权本来就存在——重复授予是幂等的，
 * 返回成功而非失败。
 *
 * @param acl                            生效或已存在的授权条目
 * @param alreadyGranted                 是否命中已有授权
 * @param clearanceBlockedPrincipalCount 因主体密级不足而当前无法读取内容的主体数量
 */
@Schema(name = "SpaceAclGrantResult", description = "授权授予结果")
public record SpaceAclGrantResult(
        @Schema(description = "生效或已存在的授权条目")
        SpaceAclSummary acl,
        @Schema(description = "是否命中已有授权", example = "false")
        boolean alreadyGranted,
        @Schema(description = "因密级不足而当前无法读取内容的主体数量", example = "1")
        long clearanceBlockedPrincipalCount) {
}
