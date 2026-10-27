package com.zhishiku.kb.app.auth.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 本地用户名密码登录请求。
 *
 * @param username 登录用户名
 * @param password 明文密码，仅在本次请求内校验，不落库
 */
@Schema(name = "LoginRequest", description = "本地用户名密码登录请求")
public record LoginRequest(
        @Schema(description = "用户名", example = "alice") @NotBlank String username,
        @Schema(description = "密码") @NotBlank String password) {
}
