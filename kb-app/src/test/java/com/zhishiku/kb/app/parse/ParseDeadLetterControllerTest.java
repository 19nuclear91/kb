package com.zhishiku.kb.app.parse;

import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.IdentityManagementAuthorizer;
import com.zhishiku.kb.app.auth.service.IdentityService;
import com.zhishiku.kb.app.parse.service.ParseDeadLetterService;
import com.zhishiku.kb.app.parse.web.ParseDeadLetterController;
import com.zhishiku.kb.app.parse.web.ParseDeadLetterReplayRequest;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseDeadLetterControllerTest {
    private final ParseDeadLetterService service = mock(ParseDeadLetterService.class);
    private final IdentityManagementAuthorizer auth = mock(IdentityManagementAuthorizer.class);
    private final IdentityService identities = mock(IdentityService.class);
    private final ParseDeadLetterController controller = new ParseDeadLetterController(service, auth, identities);

    @Test
    void readAndReplayUseIndependentCurrentPermissions() {
        when(auth.require(Permission.PARSE_DEADLETTER_READ)).thenReturn(42L);
        controller.list(1, 20, null, null, null, null, "query");
        verify(service).list(1, 20, null, null, null, null, 42L, "query");
        when(auth.require(Permission.PARSE_DEADLETTER_REPLAY)).thenReturn(43L);
        controller.replay(9, new ParseDeadLetterReplayRequest("已恢复"), "write");
        verify(service).replay(43L, 9, "write", "已恢复");
    }

    @Test
    void deniedOrDisabledIdentityNeverReachesServiceIncludingRetries() {
        when(auth.require(Permission.PARSE_DEADLETTER_READ)).thenThrow(IdentityException.forbidden("无资格"));
        when(auth.require(Permission.PARSE_DEADLETTER_REPLAY)).thenThrow(IdentityException.forbidden("主体停用或已撤权"));
        assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null, "query"))
                .isInstanceOf(IdentityException.class);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> controller.replay(9, new ParseDeadLetterReplayRequest("原因"), "original"))
                    .isInstanceOf(IdentityException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void disabledDepartmentCannotUseGrantedPermissionsOrReplayReceipts() {
        when(auth.require(Permission.PARSE_DEADLETTER_READ)).thenReturn(42L);
        when(auth.require(Permission.PARSE_DEADLETTER_REPLAY)).thenReturn(42L);
        when(identities.resolvePrincipal(42L)).thenThrow(IdentityException.forbidden("主部门已停用"));
        assertThatThrownBy(() -> controller.list(1, 20, null, null, null, null, "query"))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> controller.replay(9, new ParseDeadLetterReplayRequest("原因"), "old"))
                .isInstanceOf(IdentityException.class);
        verifyNoInteractions(service);
    }
}
