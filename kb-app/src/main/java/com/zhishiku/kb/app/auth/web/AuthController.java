package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.model.LoginResult;
import com.zhishiku.kb.app.auth.model.SessionIdentity;
import com.zhishiku.kb.app.auth.service.LocalAuthService;
import com.zhishiku.kb.app.auth.web.dto.LoginRequest;
import com.zhishiku.kb.app.auth.web.dto.PasswordChangeRequest;
import com.zhishiku.kb.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地会话入口：登录、登出、会话主体查询与自助改密。
 *
 * <p>本控制器无条件注册（设计 D3）：logout、me 与 password 不受开发态开关
 * 约束，生产环境同样需要；开发态登录仍由 {@link DevAuthController} 在
 * {@code kb.dev-auth.enabled=true} 时提供。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "本地登录、会话查询、登出与改密")
public class AuthController {

    private final LocalAuthService localAuthService;

    public AuthController(LocalAuthService localAuthService) {
        this.localAuthService = localAuthService;
    }

    @PostMapping("/login")
    @Operation(summary = "用户名密码登录", description = "校验本地凭据并建立 Sa-Token 会话")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "登录成功",
                    content = @Content(schema = @Schema(implementation = R.class))),
            @ApiResponse(responseCode = "401", description = "用户名或密码错误"),
            @ApiResponse(responseCode = "403", description = "账户或主部门已停用")
    })
    public R<LoginResult> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(localAuthService.login(request.username(), request.password()));
    }

    @PostMapping("/logout")
    @Operation(summary = "注销", description = "注销当前 Sa-Token 会话")
    public R<Void> logout() {
        StpUtil.logout();
        return R.ok();
    }

    @GetMapping("/me")
    @Operation(summary = "当前会话主体",
            description = "返回服务端解析的用户身份、角色与强制改密标记，不采信客户端声明")
    public R<SessionIdentity> me() {
        return R.ok(localAuthService.sessionIdentity(StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/password")
    @Operation(summary = "修改密码", description = "校验原密码与强度约束后更新凭据并清除强制改密标记")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "修改成功"),
            @ApiResponse(responseCode = "400", description = "原密码错误或新密码不满足强度约束")
    })
    public R<Void> changePassword(@Valid @RequestBody PasswordChangeRequest request,
                                  @RequestHeader(value = "X-Request-Id", required = false)
                                  String headerRequestId) {
        // 请求标识：body 字段优先、X-Request-Id 头回退；都缺省时由服务端生成
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? headerRequestId : request.requestId();
        localAuthService.changePassword(StpUtil.getLoginIdAsLong(), request.oldPassword(),
                request.newPassword(), requestId);
        return R.ok();
    }
}
