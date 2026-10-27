package com.zhishiku.kb.app.auth.web;

import cn.dev33.satoken.stp.StpUtil;
import com.zhishiku.kb.app.auth.service.AuthService;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.QuotaPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 向受信任的内部服务提供当前会话的配额主体。
 *
 * <p>调用者身份由 Sa-Token 会话解析，绝不接受 userId 或 departmentId 参数。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "内部服务使用的会话主体信息")
public class QuotaContextController {

    private final AuthService authService;

    public QuotaContextController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/quota-context")
    @Operation(summary = "获取当前配额主体", description = "仅返回已认证用户及其所属部门 ID")
    /**
     * 返回当前会话解析出的配额主体。
     *
     * <p>接口没有业务入参，用户和部门均来自服务端会话及持久化身份事实。
     *
     * @return 当前用户内部 ID及其主部门 ID
     */
    public R<QuotaPrincipal> quotaContext() {
        return R.ok(authService.quotaPrincipal(StpUtil.getLoginIdAsLong()));
    }
}
