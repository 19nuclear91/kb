package com.zhishiku.kb.app.config;

import cn.dev33.satoken.exception.NotLoginException;
import com.zhishiku.kb.common.api.R;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理的状态码语义（任务 6.2）：未认证 401、无资格 403。
 *
 * <p>直接实例化 advice 调用处理方法，不起 Spring 上下文：本次改动的全部
 * 内容就是状态码映射，直接调处理器测的就是这条映射本身。两条路径必须分开
 * 断言——{@code SaTokenException}（未认证，会话缺失或失效）与
 * {@code IdentityException.forbidden}（已认证但无资格）在 kb-app 是两条
 * 互不交叉的异常链，若把 403 一并折进 401，调用方将无法区分"重新登录"
 * 与"申请权限"。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("未认证（SaTokenException）：401 且响应体为 R.fail（任务 6.2）")
    void unauthenticatedSaTokenExceptionGets401() {
        ResponseEntity<R<Void>> response = handler.handleAuth(new NotLoginException(
                NotLoginException.NOT_TOKEN_MESSAGE, NotLoginException.NOT_TOKEN, null));

        assertThat(response.getStatusCode())
                .as("未认证是 401（会话缺失或失效），403 留给已认证但无资格")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .as("HTTP 状态承载语义，业务码沿用 R.fail 的 500 约定")
                .isEqualTo(R.FAILURE);
        assertThat(response.getBody().message()).isEqualTo("登录状态无效或已过期");
        assertThat(response.getBody().data()).isNull();
    }

    @Test
    @DisplayName("无资格（IdentityException.forbidden）：仍为 403（任务 6.2）")
    void forbiddenIdentityExceptionStays403() {
        ResponseEntity<R<Void>> response =
                handler.handleIdentity(IdentityException.forbidden("无权执行身份管理操作"));

        assertThat(response.getStatusCode())
                .as("已认证但无资格仍是 403，不能被未认证的 401 语义吞并")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("无权执行身份管理操作");
    }
}
