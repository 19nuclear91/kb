package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAuditServiceTest {

    private final InMemoryAuditStore store = new InMemoryAuditStore();
    private final LoginAuditService audit = new LoginAuditService(store);

    @Test
    void successWritesExactlyOneEventWithServerGeneratedRequestId() {
        audit.success(7L, "alice");

        assertThat(store.events()).hasSize(1);
        IdentityAuditRecord event = store.events().get(0);
        assertThat(event.getOperationScope()).isEqualTo("AUTH_LOGIN_SUCCESS");
        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        assertThat(event.getTargetType()).isEqualTo("auth");
        assertThat(event.getActorUserId()).isEqualTo(7L);
        assertThat(event.getTargetId()).isEqualTo(7L);
        assertThat(UUID.fromString(event.getRequestId())).isNotNull();
        assertThat(event.getAfterState()).contains("alice");
    }

    @Test
    void failureWritesExactlyOneEventWithAttemptedUsernameOnly() {
        audit.failure("alice", null, "凭据校验失败");
        audit.failure("bob", 9L, "用户或部门已停用");

        assertThat(store.events()).hasSize(2);
        IdentityAuditRecord unknown = store.events().get(0);
        assertThat(unknown.getOperationScope()).isEqualTo("AUTH_LOGIN_FAILURE");
        assertThat(unknown.getStatus()).isEqualTo("FAILED");
        assertThat(unknown.getTargetId()).isNull();
        assertThat(unknown.getAfterState()).contains("alice");
        assertThat(unknown.getReason()).isEqualTo("凭据校验失败");

        IdentityAuditRecord disabled = store.events().get(1);
        assertThat(disabled.getTargetId()).isEqualTo(9L);
        assertThat(disabled.getReason()).isEqualTo("用户或部门已停用");

        for (IdentityAuditRecord event : store.events()) {
            assertThat(event.getAfterState() + event.getBeforeState() + event.getReason())
                    .doesNotContain("password", "token", "secret", "hash");
        }
    }

    @Test
    void requestIdsAreFreshPerEvent() {
        audit.success(7L, "alice");
        audit.failure("alice", 7L, "凭据校验失败");

        assertThat(store.events())
                .hasSize(2)
                .extracting(IdentityAuditRecord::getRequestId)
                .doesNotHaveDuplicates();
    }

    @Test
    void usernameIsEscapedBeforeEnteringTheJsonbSummary() {
        audit.failure("bad\"name\\suffix", null, "凭据校验失败");

        assertThat(store.events().get(0).getAfterState())
                .isEqualTo("{\"username\":\"bad\\\"name\\\\suffix\"}");
    }
}
