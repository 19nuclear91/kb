package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.IdentityAuditMapper;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityAuditServiceTest {

    private final IdentityAuditMapper mapper = mock(IdentityAuditMapper.class);
    private final IdentityAuditService service = new IdentityAuditService(mapper,
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void strictSuccessPersistsReasonAndDoesNotRecoverFromInsertFailure() {
        when(mapper.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(1);
        var event = service.successWithReason(AuditScope.DOCUMENT_SECURITY_LEVEL_CHANGE, "doc-r", 1L, 7L,
                "change_security_level", "{\"securityLevel\":1}", "{\"securityLevel\":2}", "定级复核");
        assertThat(event.getReason()).isEqualTo("定级复核");
        assertThat(event.getTargetType()).isEqualTo("document");
        assertThat(event.getTargetId()).isEqualTo(7);
        when(mapper.list(any(), any(), any(), any())).thenReturn(List.of(event));
        assertThat(service.list("document", 7L, null, null)).containsExactly(event);
        var failure = new DataIntegrityViolationException("failed");
        when(mapper.insert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(failure);
        assertThatThrownBy(() -> service.successWithReason(AuditScope.DOCUMENT_SECURITY_LEVEL_CHANGE, "doc-r", 1L,
                7L, "change_security_level", "{}", "{}", "原因")).isSameAs(failure);
        verify(mapper, never()).findByRequest(any(), any());
    }

    @Test
    void writesOneSafeSuccessEventAndReusesExistingRequest() {
        when(mapper.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(1);

        IdentityAuditRecord created = service.success(AuditScope.USER_ENABLE, "req-1", 7L,
                7L, "enable", "{\"enabled\":false}",
                "{\"enabled\":true}");

        assertThat(created.getStatus()).isEqualTo("SUCCESS");
        assertThat(created.getAfterState()).doesNotContain("token", "subject");

        when(mapper.findByRequest("USER_ENABLE", "req-2"))
                .thenReturn(created);
        IdentityAuditRecord existing = service.success(AuditScope.USER_ENABLE, "req-2", 7L,
                7L, "enable", "{}", "{}");
        assertThat(existing).isSameAs(created);
        verify(mapper, never()).insert(any(), any(), ArgumentMatchers.eq("req-2"),
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void failsWhenAuditCannotBePersisted() {
        when(mapper.insert(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.success(AuditScope.DEPARTMENT_MOVE, "req-2", 7L,
                2L, "move", "{}", "{}"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("审计");
    }

    @Test
    void queriesTargetAndTimeRangeWithoutReconstructingSensitiveValues() {
        IdentityAuditRecord event = new IdentityAuditRecord(null, null, "USER_ENABLE", "r-1",
                1L, "user", 7L, "enable", "SUCCESS", "{\"enabled\":false}",
                "{\"enabled\":true}", null, Instant.parse("2026-10-01T00:00:00Z"));
        when(mapper.list(any(), any(), any(), any())).thenReturn(List.of(event));

        assertThat(service.list("user", 7L,
                Instant.parse("2026-09-30T00:00:00Z"),
                Instant.parse("2026-10-02T00:00:00Z")))
                .containsExactly(event)
                .allMatch(item -> !item.getBeforeState().contains("token")
                        && !item.getAfterState().contains("subject"));
        assertThatThrownBy(() -> service.list("user", 7L,
                Instant.parse("2026-10-02T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z")))
                .isInstanceOf(IdentityException.class);
    }
}
