package com.zhishiku.kb.app.auth.service;

import com.zhishiku.kb.app.auth.mapper.AuditQueryMapper;
import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AuditQuerySourceTest {
    @Test
    void ssoEventsUseExistingReadSourceWithoutEnrichingUnknownUser() {
        var mapper = mock(AuditQueryMapper.class);
        var source = new AuditQuerySource(mapper);
        var filter = new AuditQueryFilter(null, "SSO_LOGIN", "login", "user", null, "FAILED", null, null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, null, 21);
        var row = new IdentityAuditRecord();
        row.setOperationScope("SSO_LOGIN");
        row.setTargetType("user");
        row.setAfterState("{\"platform\":\"feishu\",\"resultCode\":\"BINDING_REQUIRED\"}");
        when(mapper.list(filter)).thenReturn(List.of(row));
        assertThat(source.list(filter)).singleElement().satisfies(event -> {
            assertThat(event.getTargetId()).isNull();
            assertThat(event.getActorUserId()).isNull();
            assertThat(event.getOperationScope()).isEqualTo("SSO_LOGIN");
        });
        verify(mapper).list(filter);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void forwardsOnlyReadCallsAndPreservesOrphanMetadata() {
        var mapper = mock(AuditQueryMapper.class);
        var source = new AuditQuerySource(mapper);
        var filter = new AuditQueryFilter(null, null, null, null, null, null, null, null,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(60), null, null, 21);
        var row = new IdentityAuditRecord();
        var eventId = UUID.randomUUID();
        row.setEventId(eventId);
        when(mapper.list(filter)).thenReturn(List.of(row));
        when(mapper.detail(eventId)).thenReturn(row);
        assertThat(source.list(filter)).containsExactly(row);
        assertThat(source.detail(eventId).getTargetId()).isNull();
        verify(mapper).list(filter);
        verify(mapper).detail(eventId);
        verifyNoMoreInteractions(mapper);
    }
}
