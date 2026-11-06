package com.zhishiku.kb.app.auth.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.auth.mapper.IdentityDepartmentMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleInboxMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleMapper;
import com.zhishiku.kb.app.auth.mapper.LifecycleQueryMapper;
import com.zhishiku.kb.app.auth.model.IdentityRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.EpochSessionService;
import com.zhishiku.kb.app.query.QueryException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LifecycleQueryServiceTest {
    private final LifecycleAuthorizer auth = mock(LifecycleAuthorizer.class);
    private final IdentityDepartmentMapper users = mock(IdentityDepartmentMapper.class);
    private final LifecycleMapper mappings = mock(LifecycleMapper.class);
    private final LifecycleInboxMapper inbox = mock(LifecycleInboxMapper.class);
    private final LifecycleQueryMapper queries = mock(LifecycleQueryMapper.class);
    private final LifecycleQueryService service = new LifecycleQueryService(auth, users, mappings, inbox, queries,
            DingTalkLifecycleSettingsTest.configured(false), mock(DingTalkStreamConnection.class));

    @Test
    void noReadPermissionNeverQueriesEvenWithWritePermissionOrMissingTarget() {
        doThrow(IdentityException.forbidden("缺少权限")).when(auth).require("token", Permission.IDENTITY_LIFECYCLE_READ);
        assertThatThrownBy(() -> service.events("token", Map.of())).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.user("token", "999")).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.status("token")).isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> service.emergency("token", Map.of())).isInstanceOf(IdentityException.class);
        verifyNoInteractions(users, mappings, inbox, queries);
    }

    @Test
    void cursorUsesInternalDescendingIdAndBindsFilterWithoutGlobalTotal() throws Exception {
        when(queries.events("UNMAPPED", null, 2)).thenReturn(List.of(event(12), event(9)));
        var first = service.events("token", Map.of("status", List.of("UNMAPPED"), "limit", List.of("1")));
        assertThat(first.items()).extracting(LifecycleViews.Event::id).containsExactly(12L);
        assertThat(first.hasMore()).isTrue();
        when(queries.events("UNMAPPED", 12L, 2)).thenReturn(List.of(event(9)));
        var second = service.events("token", Map.of("status", List.of("UNMAPPED"), "limit", List.of("1"), "cursor", List.of(first.nextCursor())));
        assertThat(second.items()).extracting(LifecycleViews.Event::id).containsExactly(9L);
        assertThat(second.hasMore()).isFalse();
        assertThatThrownBy(() -> service.events("token", Map.of("status", List.of("REVIEW"), "cursor", List.of(first.nextCursor()))))
                .isInstanceOf(QueryException.class);
        var serialized = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(first);
        assertThat(serialized).doesNotContain("total", "employeeId", "enterpriseId", "contentDigest", "sourceScope");
    }

    @Test
    void corruptOrUnavailableIdentityIsNeverPresentedAsUnmanaged() {
        var user = new IdentityRecord(7L, "local", "用户", 1L, (short) 1, false, 0L, null, null, true, 2L, "OFFBOARDED");
        when(users.findIdentity(7)).thenReturn(user);
        assertThatThrownBy(() -> service.user("token", "7")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
        user.setLifecycleRestriction("UNKNOWN");
        assertThatThrownBy(() -> service.user("token", "7")).isInstanceOf(ApiException.class);
        when(users.findIdentity(7)).thenThrow(new IllegalStateException("dependency"));
        assertThatThrownBy(() -> service.user("token", "7")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void summaryContainsNoExternalIdentityEvenForManagedAccount() throws Exception {
        var user = new IdentityRecord(7L, "local", "用户", 1L, (short) 1, false, 2L, null, null, true, 2L, "OFFBOARDED");
        when(users.findIdentity(7)).thenReturn(user);
        when(mappings.findAssignment(7)).thenReturn(new LifecycleAssignment(7, LifecycleAssignment.Kind.MANAGED, "dingtalk",
                "private-corp", "private-employee", "internal", LifecycleAssignment.Restriction.OFFBOARDED, 2, 11L, null, 8L, null, null));
        var result = service.user("token", "7");
        assertThat(result.manualEnabled()).isTrue();
        assertThat(result.enabled()).isFalse();
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("private-corp", "private-employee", "employeeId");
        verify(auth).require("token", Permission.IDENTITY_LIFECYCLE_READ);
    }

    private static LifecycleViews.Event event(long id) {
        return new LifecycleViews.Event(id, "user_leave_org", false, Instant.EPOCH, Instant.EPOCH, 0, 0, 1, 0);
    }
}
