package com.zhishiku.kb.app.auth.model;

import com.zhishiku.kb.common.security.IdentitySummary;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sa-Token 登录结果。生产环境应由 SSO 登录后由本服务建立会话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "LoginResult", description = "登录令牌信息")
public class LoginResult {

    @Schema(description = "认证请求头名称", example = "satoken")
    private String tokenName;

    @Schema(description = "认证令牌值")
    private String tokenValue;

    @Schema(description = "登录用户 ID", example = "1")
    private long loginId;

    @Schema(description = "是否必须先完成密码修改", example = "false")
    private boolean mustChangePassword;

    @Schema(description = "登录用户摘要")
    private IdentitySummary user;
}
