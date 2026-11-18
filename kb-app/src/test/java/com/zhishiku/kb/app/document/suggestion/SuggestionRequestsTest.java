package com.zhishiku.kb.app.document.suggestion;

import com.zhishiku.kb.app.document.metadata.service.MetadataException;
import com.zhishiku.kb.common.api.ApiException;
import com.zhishiku.kb.common.security.IdentityException;
import com.zhishiku.kb.common.suggestion.SuggestionData;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SuggestionRequestsTest {
    private final SuggestionAccess access = mock(SuggestionAccess.class);
    private final SuggestionStore store = mock(SuggestionStore.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final SuggestionRequests service = new SuggestionRequests(access, store, events, true, clock);
    private final UUID id = UUID.randomUUID();
    private final SuggestionAccess.Context context = new SuggestionAccess.Context(1, 2, 3, 4, 5, 6L, 7, 8);

    private SuggestionJob job(UUID execution, String state) {
        return new SuggestionJob(id, execution, 1, 2, 3, 4, 5, 6, 7, 8, OffsetDateTime.now(clock),
                state, null, null, null, null, null, null, null, null, "NOT_REQUIRED");
    }

    @Test
    void firstVersionAwaitingPublicationCannotStartGeneration() {
        when(access.current("token", 4)).thenReturn(new SuggestionAccess.Context(1, 2, 3, 4, 5, null, 7, 8));
        var error = assertThrows(MetadataException.class, () -> service.create("token", 4, 9L, 7, id));
        assertEquals(MetadataException.Reason.NO_PUBLISHED_VERSION, error.reason());
        verify(store, never()).insert(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any(SuggestionRequests.Start.class));
    }

    @Test
    void onlyPublishedVersionCanBeScheduledWhenAnotherVersionIsPending() {
        when(access.current("token", 4)).thenReturn(context);
        var error = assertThrows(MetadataException.class, () -> service.create("token", 4, 9L, 7, id));
        assertEquals(MetadataException.Reason.SOURCE_CHANGED, error.reason());
        verify(store, never()).insert(any(), any(), any(), any(), any());
        verify(events, never()).publishEvent(any(SuggestionRequests.Start.class));

        when(store.insert(eq(id), any(), eq(context), any(), any())).thenReturn(true);
        when(store.find(id)).thenReturn(null, job(UUID.randomUUID(), "RUNNING"));
        assertEquals(6L, service.create("token", 4, 6L, 7, id).sourceVersionId());
        verify(events).publishEvent(any(SuggestionRequests.Start.class));
    }

    @Test
    void cancelReadsCurrentDirectoryAfterWaitingForCompletion() {
        var visible = new AtomicReference<>(context);
        when(access.current("token", 4)).thenAnswer(call -> visible.get());
        when(store.lockOwned(id, 1, 4)).thenAnswer(call -> {
            visible.set(new SuggestionAccess.Context(1, 2, 3, 4, 5, 6L, 7, 9));
            return job(UUID.randomUUID(), "COMPLETED");
        });
        var view = service.cancel("token", 4, id);
        assertEquals("COMPLETED", view.status());
        assertEquals("TREE_VERSION_CONFLICT", view.reasonCode());
        assertNull(view.result());
        verify(store, never()).result(any(), any());
        verify(events, never()).publishEvent(any(SuggestionRequests.Stop.class));
    }

    @Test
    void replayAfterConcurrentRegistrationWithholdsSupersededSource() {
        var published = new SuggestionAccess.Context(1, 2, 3, 4, 5, 9L, 7, 8);
        when(access.current("token", 4)).thenReturn(context, published);
        when(store.find(id)).thenReturn(null, job(UUID.randomUUID(), "COMPLETED"));
        when(store.insert(eq(id), any(), eq(context), any(), any())).thenReturn(false);
        var view = service.create("token", 4, 6L, 7, id);
        assertEquals("SOURCE_CHANGED", view.reasonCode());
        assertNull(view.result());
        verify(store, never()).result(any(), any());
        verify(events, never()).publishEvent(any(SuggestionRequests.Start.class));
    }

    @Test
    void concurrentRegistrationAndLostResponseShareOneExecution() throws Exception {
        var rows = new ConcurrentHashMap<UUID, SuggestionJob>();
        when(access.current("token", 4)).thenReturn(context);
        when(store.find(id)).thenAnswer(call -> rows.get(id));
        when(store.insert(eq(id), any(), eq(context), any(), any())).thenAnswer(call ->
                rows.putIfAbsent(id, job(call.getArgument(1), "RUNNING")) == null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.create("token", 4, 6L, 7, id));
            var second = pool.submit(() -> service.create("token", 4, 6L, 7, id));
            assertEquals(first.get(), second.get());
        }
        assertEquals(service.get("token", 4, id), service.create("token", 4, 6L, 7, id));
        var mismatch = assertThrows(MetadataException.class, () -> service.create("token", 4, 9L, 7, id));
        assertEquals(MetadataException.Reason.REQUEST_MISMATCH, mismatch.reason());
        verify(events, times(1)).publishEvent(any(SuggestionRequests.Start.class));
    }

    @Test
    void unauthorizedCallerCannotReadOrCreateResult() {
        for (var denial : List.of(IdentityException.unauthorized("会话过期"), IdentityException.disabled("主体停用"),
                IdentityException.passwordChangeRequired("需要改密"), ApiException.notFound("不可见"), ApiException.forbidden("不可编辑"))) {
            String token = denial.getMessage();
            when(access.current(token, 4)).thenThrow(denial);
            assertEquals(denial.httpStatus(), assertThrows(ApiException.class,
                    () -> service.create(token, 4, 6L, 7, id)).httpStatus());
        }
        when(store.find(id)).thenReturn(job(UUID.randomUUID(), "COMPLETED"));
        when(access.current("other", 4)).thenReturn(new SuggestionAccess.Context(9, 2, 3, 4, 5, 6L, 7, 8));
        assertEquals(MetadataException.Reason.NOT_FOUND,
                assertThrows(MetadataException.class, () -> service.get("other", 4, id)).reason());
        verify(store, never()).result(any(), any());
    }

    @Test
    void resultExpiryHidesPayloadAndReplayDoesNotRestart() {
        var now = OffsetDateTime.now(clock);
        var row = new SuggestionStore.StoredResult(now.plusSeconds(1), "{}");
        var value = new SuggestionData.Result(null, null, null);
        when(access.current("token", 4)).thenReturn(context);
        when(store.find(id)).thenReturn(job(UUID.randomUUID(), "COMPLETED"));
        when(store.result(id, now)).thenReturn(row);
        when(store.decode(row)).thenReturn(value);
        assertEquals(value, service.get("token", 4, id).result());
        var later = new SuggestionRequests(access, store, events, true, Clock.offset(clock, java.time.Duration.ofSeconds(2)));
        var expired = later.create("token", 4, 6L, 7, id);
        assertEquals("SUGGESTION_EXPIRED", expired.reasonCode());
        assertNull(expired.result());
        later.cleanExpired();
        verify(store).clean(now.plusSeconds(2));
        verify(events, never()).publishEvent(any(SuggestionRequests.Start.class));
    }
}
