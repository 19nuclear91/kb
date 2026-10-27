package com.zhishiku.kb.app.auth.model;

import com.zhishiku.kb.common.security.IdentitySummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 当前会话主体（GET /api/auth/me 的响应）。
 *
 * <p>全部字段由服务端依据已验证会话解析：请求携带的用户、部门、角色或密级
 * 声明不参与计算（规格：会话主体查询必须只依据已验证会话）。
 *
 * @param user               用户身份摘要
 * @param roles              角色 ID 列表
 * @param mustChangePassword 是否必须先完成密码修改
 */
@Schema(name = "SessionIdentity", description = "当前会话主体的服务端身份事实")
public record SessionIdentity(
        @Schema(description = "用户身份摘要") IdentitySummary user,
        @Schema(description = "角色 ID 列表") List<Long> roles,
        @Schema(description = "是否必须先完成密码修改") boolean mustChangePassword) {

    public SessionIdentity {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
