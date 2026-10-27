package com.zhishiku.kb.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityExceptionTest {

    @Test
    void unauthorizedFactoryCarries401AndCode() {
        IdentityException e = IdentityException.unauthorized("用户名或密码错误");
        assertThat(e.httpStatus()).isEqualTo(401);
        assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.UNAUTHORIZED);
        assertThat(e.getMessage()).isEqualTo("用户名或密码错误");
    }

    @Test
    void passwordChangeRequiredFactoryCarries403AndCode() {
        IdentityException e = IdentityException.passwordChangeRequired("需先完成密码修改");
        assertThat(e.httpStatus()).isEqualTo(403);
        assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED);
    }

    @Test
    void existingFactoriesKeepTheirStatuses() {
        assertThat(IdentityException.notFound("x").httpStatus()).isEqualTo(404);
        assertThat(IdentityException.forbidden("x").httpStatus()).isEqualTo(403);
        assertThat(IdentityException.disabled("x").httpStatus()).isEqualTo(403);
        assertThat(IdentityException.badRequest("x").httpStatus()).isEqualTo(400);
        assertThat(IdentityException.conflict(IdentityErrorCode.CONFLICT, "x").httpStatus()).isEqualTo(409);
        assertThat(IdentityException.conflict(IdentityErrorCode.INVALID_VERSION, "x").errorCode())
                .isEqualTo(IdentityErrorCode.INVALID_VERSION);
    }

    @Test
    void conflictFactoryStillRejectsNonConflictCodes() {
        assertThatThrownBy(() -> IdentityException.conflict(IdentityErrorCode.UNAUTHORIZED, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityException.conflict(IdentityErrorCode.PASSWORD_CHANGE_REQUIRED, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
