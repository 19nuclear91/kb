package com.zhishiku.kb.app.search.term;

import com.zhishiku.kb.app.audit.model.AuditQueryViews.Detail;
import com.zhishiku.kb.app.audit.service.AuditQueryService;
import com.zhishiku.kb.app.audit.service.AuditSummaryProjector;
import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.AuditQuerySource;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.query.QueryException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TermAuditQueryTest {
    @Test
    void allTerminologyScopesAndTargetsUseExistingExactFiltersAndUnknownLegalCodesRemainAccepted() {
        for (AuditScope scope : List.of(AuditScope.SEARCH_TERM_CREATE, AuditScope.SEARCH_TERM_UPDATE,
                AuditScope.SEARCH_CORRECTION_CREATE, AuditScope.SEARCH_CORRECTION_UPDATE)) {
            var source = mock(AuditQuerySource.class);
            var access = mock(AdminQueryAccess.class);
            var service = service(source, access, mock(AuditSummaryProjector.class));
            assertThat(service.list(Map.of("operationScope", List.of(scope.name()), "targetType", List.of(scope.targetType()),
                    "targetId", List.of(Long.toString(Long.MAX_VALUE)))).items()).isEmpty();
            var captured = ArgumentCaptor.forClass(AuditQueryFilter.class);
            verify(source).list(captured.capture());
            assertThat(captured.getValue().operationScope()).isEqualTo(scope.name());
            assertThat(captured.getValue().targetType()).isEqualTo(scope.targetType());
            assertThat(captured.getValue().targetId()).isEqualTo(Long.MAX_VALUE);
            verify(access).require(Permission.AUDIT_READ);
        }
        var source = mock(AuditQuerySource.class);
        service(source, mock(AdminQueryAccess.class), mock(AuditSummaryProjector.class)).list(Map.of(
                "operationScope", List.of("FUTURE_SCOPE"), "targetType", List.of("future-target")));
        var captured = ArgumentCaptor.forClass(AuditQueryFilter.class);
        verify(source).list(captured.capture());
        assertThat(captured.getValue().operationScope()).isEqualTo("FUTURE_SCOPE");
        assertThat(captured.getValue().targetType()).isEqualTo("future-target");
    }

    @Test
    void missingAuditPermissionDeniesBeforeAnyEventLookupAndDoesNotUseTermManagementPermission() {
        var source = mock(AuditQuerySource.class);
        var access = mock(AdminQueryAccess.class);
        doThrow(QueryException.forbidden()).when(access).require(Permission.AUDIT_READ);
        var projector = mock(AuditSummaryProjector.class);
        var service = service(source, access, projector);
        assertThatThrownBy(() -> service.list(Map.of("operationScope", List.of("SEARCH_TERM_UPDATE"))))
                .isInstanceOfSatisfying(QueryException.class, error -> assertThat(error.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.detail(UUID.randomUUID().toString(), Map.of())).isInstanceOf(QueryException.class);
        verifyNoInteractions(source, projector);
    }

    @Test
    void disabledOrUnavailableCurrentTargetDoesNotPreventReadingStoredEventByUuid() {
        var source = mock(AuditQuerySource.class);
        var projector = mock(AuditSummaryProjector.class);
        UUID eventId = UUID.randomUUID();
        var historic = new IdentityAuditRecord(1L, eventId, "SEARCH_TERM_UPDATE", "request", 7L, "search-term", 9L,
                "update", "SUCCESS", "{}", "{}", null, Instant.EPOCH);
        Detail expected = mock(Detail.class);
        when(source.detail(eventId)).thenReturn(historic);
        when(projector.detail(historic)).thenReturn(expected);
        assertThat(service(source, mock(AdminQueryAccess.class), projector).detail(eventId.toString(), Map.of())).isSameAs(expected);
        verify(source).detail(eventId);
        verify(projector).detail(historic);
    }

    private static AuditQueryService service(AuditQuerySource source, AdminQueryAccess access, AuditSummaryProjector projector) {
        return new AuditQueryService(source, access, projector, Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC));
    }
}
