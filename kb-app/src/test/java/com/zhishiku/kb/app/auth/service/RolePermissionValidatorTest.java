package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.RolePermissionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class RolePermissionValidatorTest {
    @Test
    void unknownCodeFailsStartupAndIsReported() {
        var mapper = mock(RolePermissionMapper.class);
        when(mapper.findAllPermissionCodes()).thenReturn(List.of("space:*"));
        assertThatThrownBy(() -> new RolePermissionValidator(mapper).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("space:*");
    }

    @Test
    void incompleteAdminPermissionsWarnWithoutFailing(CapturedOutput output) {
        var mapper = mock(RolePermissionMapper.class);
        when(mapper.findAllPermissionCodes()).thenReturn(List.of("identity:user:read"));
        when(mapper.findPermissionsByRoleCode("ROLE_ADMIN")).thenReturn(List.of("identity:user:read"));
        assertThatCode(() -> new RolePermissionValidator(mapper).run(null)).doesNotThrowAnyException();
        assertThat(output.getOut()).contains("WARN", "ROLE_ADMIN", "identity:role:write");
    }
}
