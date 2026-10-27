package com.zhishiku.kb.app.auth.web;

import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发态登录入口。它只接受种子用户 ID，不代表生产认证方案；生产登录走
 * {@link AuthController#login} 的本地凭据校验。整个控制器仅在
 * {@code kb.dev-auth.enabled=true} 时注册。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "开发态登录（仅 kb.dev-auth.enabled=true 时可用）")
@Validated
@ConditionalOnProperty(name = "kb.dev-auth.enabled", havingValue = "true")
public class DevAuthController {

    private final AuthService authService;

    public DevAuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/dev-login")
    @Operation(summary = "开发态登录", description = "仅开发环境使用种子用户 ID 换取 Sa-Token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功",
                    content = @Content(schema = @Schema(implementation = R.class))),
            @ApiResponse(responseCode = "500", description = "用户不存在或登录失败")
    })
    /**
     * 使用开发种子用户建立 Sa-Token 会话。
     *
     * @param userId 开发环境中的用户内部 ID
     * @return 登录结果和会话标识摘要
     */
    public R<LoginResult> devLogin(
            @Parameter(description = "开发种子用户 ID", required = true, example = "1")
            @Positive @RequestParam long userId) {
        return R.ok(authService.devLogin(userId));
    }
}
