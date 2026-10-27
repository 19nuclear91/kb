package com.zhishiku.kb.app.audit.service;

import com.zhishiku.kb.app.auth.model.AuditQueryFilter;
import com.zhishiku.kb.app.auth.model.IdentityAuditRecord;
import com.zhishiku.kb.app.auth.model.Permission;
import com.zhishiku.kb.app.auth.service.AdminQueryAccess;
import com.zhishiku.kb.app.auth.service.AuditQuerySource;
import com.zhishiku.kb.app.query.QueryCursor;
import com.zhishiku.kb.app.query.QueryException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuditQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00.123456789Z");

    @Test
    void defaultWindowIsPinnedAcrossRequestsAndTimeIdPositionUsesLastVisible() {
        var source = mock(AuditQuerySource.class);
        var access = mock(AdminQueryAccess.class);
        var service = service(source, access, NOW);
        Instant eventTime = Instant.parse("2026-10-16T00:00:00Z");
        when(source.list(any())).thenReturn(List.of(row(9, eventTime), row(8, eventTime), row(7, eventTime)));
        var first = service.list(Map.of("limit", List.of("2")));
        assertThat(first.items()).hasSize(2);
        assertThat(first.items().getFirst().actorUserId()).isNull();
        assertThat(first.items().getFirst().targetId()).isNull();
        var cursor = QueryCursor.decode(first.nextCursor(), "audit");
        assertThat(cursor.id()).isEqualTo(8);
        assertThat(cursor.time()).isEqualTo(eventTime);
        assertThat(cursor.to()).isEqualTo(Instant.parse("2026-10-17T00:00:00.123456Z"));
        assertThat(cursor.from()).isEqualTo(Instant.parse("2026-10-10T00:00:00.123456Z"));
        assertThat(first.from()).isEqualTo(cursor.from());
        assertThat(first.to()).isEqualTo(cursor.to());
        service(source, access, NOW.plusSeconds(3600)).list(Map.of("cursor", List.of(first.nextCursor()), "limit",
                List.of("1")));
        var filters = ArgumentCaptor.forClass(AuditQueryFilter.class);
        verify(source, times(2)).list(filters.capture());
        assertThat(filters.getAllValues().get(1).to()).isEqualTo(cursor.to());
        assertThat(filters.getAllValues().get(1).beforeId()).isEqualTo(8);
        assertThat(filters.getAllValues().get(1).fetch()).isEqualTo(2);
        assertThatThrownBy(() -> service.list(Map.of("cursor", List.of(first.nextCursor()), "requestId", List.of(
                "other")))).isInstanceOf(QueryException.class);
        assertThatThrownBy(() -> service.list(Map.of("cursor", List.of(first.nextCursor()), "to", List.of("2026-10" +
                "-17T01:00:00Z")))).isInstanceOf(QueryException.class);
        var equivalent = service.list(Map.of("cursor", List.of(first.nextCursor()),
                "from", List.of("2026-10-10T08:00:00.123456789+08:00"), "to", List.of("2026-10-17T08:00:00" +
                        ".123456+08:00")));
        assertThat(equivalent.from()).isEqualTo(first.from());
        assertThat(equivalent.to()).isEqualTo(first.to());
    }

    @Test
    void exactFiltersAreCombinedWithoutUppercasingOrDroppingUnknownValues() {
        var source = mock(AuditQuerySource.class);
        when(source.list(any())).thenReturn(List.of());
        var service = service(source, mock(AdminQueryAccess.class), NOW);
        var input = Map.ofEntries(Map.entry("actorUserId", List.of("3")), Map.entry("operationScope", List.of(
                "UNKNOWN_SCOPE")),
                Map.entry("operation", List.of("unknown")), Map.entry("targetType", List.of("external-identity")),
                Map.entry("targetId", List.of("9")), Map.entry("status", List.of("FAILED")),
                Map.entry("requestId", List.of("req_%")), Map.entry("loginUsername", List.of("unknown_%")),
                Map.entry("from", List.of("2026-10-10T08:00:00.123456789+08:00")), Map.entry("to", List.of("2026-10" +
                        "-17T00:00:00.123456Z")));
        assertThat(service.list(input).items()).isEmpty();
        var filter = ArgumentCaptor.forClass(AuditQueryFilter.class);
        verify(source).list(filter.capture());
        var f = filter.getValue();
        assertThat(f.actorUserId()).isEqualTo(3);
        assertThat(f.operationScope()).isEqualTo("UNKNOWN_SCOPE");
        assertThat(f.operation()).isEqualTo("unknown");
        assertThat(f.targetType()).isEqualTo("external-identity");
        assertThat(f.targetId()).isEqualTo(9);
        assertThat(f.status()).isEqualTo("FAILED");
        assertThat(f.requestId()).isEqualTo("req_%");
        assertThat(f.loginUsername()).isEqualTo("unknown_%");
        assertThat(f.from()).isEqualTo(Instant.parse("2026-10-10T00:00:00.123456Z"));
    }

    @Test
    void invalidWindowsAndFilterShapesAreRejectedBeforeReading() {
        var source = mock(AuditQuerySource.class);
        var service = service(source, mock(AdminQueryAccess.class), NOW);
        for (Map<String, List<String>> input : List.of(
                Map.of("targetId", List.of("3")), Map.of("status", List.of("success")),
                Map.of("from", List.of("2026-10-01T00:00:00")),
                Map.of("from", List.of("2026-10-17T00:00:00Z"), "to", List.of("2026-10-17T00:00:00Z")),
                Map.of("from", List.of("2026-09-01T00:00:00Z")),
                Map.of("loginUsername", List.of("x".repeat(129))), Map.of("reason", List.of("private")))) {
            assertThatThrownBy(() -> service.list(input)).isInstanceOf(QueryException.class);
        }
        verifyNoInteractions(source);
        when(source.list(any())).thenReturn(List.of());
        var boundary = service.list(Map.of("from", List.of("2026-09-16T00:00:00Z"), "to", List.of("2026-10-17T00:00" +
                ":00Z")));
        assertThat(boundary.items()).isEmpty();
        assertThat(boundary.from()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
    }

    @Test
    void authorizationPrecedesEventExistenceAndIsRepeatedAndFailuresPropagate() {
        var source = mock(AuditQuerySource.class);
        var access = mock(AdminQueryAccess.class);
        var service = service(source, access, NOW);
        assertThatThrownBy(() -> service.detail("1-1-1-1-1", Map.of())).isInstanceOf(QueryException.class);
        String eventId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.detail(eventId, Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(404));
        when(source.detail(UUID.fromString(eventId))).thenReturn(row(2, NOW));
        assertThat(service.detail(eventId, Map.of()).metadata().attemptedUsername()).isEqualTo("unknown");
        doThrow(QueryException.forbidden()).when(access).require(Permission.AUDIT_READ);
        assertThatThrownBy(() -> service.detail(eventId, Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.detail("invalid", Map.of())).isInstanceOfSatisfying(QueryException.class,
                e -> assertThat(e.status()).isEqualTo(403));
        var failing = mock(AuditQuerySource.class);
        when(failing.list(any())).thenThrow(new DataAccessResourceFailureException("secret-sql"));
        assertThatThrownBy(() -> service(failing, mock(AdminQueryAccess.class), NOW).list(Map.of())).isInstanceOf(DataAccessResourceFailureException.class);
    }

    private static AuditQueryService service(AuditQuerySource source, AdminQueryAccess access, Instant now) {
        return new AuditQueryService(source, access, new AuditSummaryProjector(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static IdentityAuditRecord row(long id, Instant time) {
        return new IdentityAuditRecord(id, UUID.randomUUID(), "AUTH_LOGIN_FAILURE", "request", null, "auth", null,
                "login", "FAILED", "{}", "{\"username\":\"unknown\"}", "secret-reason", time);
    }
}
