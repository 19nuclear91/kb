package com.zhishiku.kb.app.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.parse.mapper.ParseDeadLetterMapper;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterView;
import com.zhishiku.kb.app.parse.model.ParseReplayBinding;
import com.zhishiku.kb.app.parse.service.ParseDeadLetterService;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseDeadLetterValidationTest {
    private final ParseDeadLetterMapper mapper = mock(ParseDeadLetterMapper.class);
    private final IdentityAuditService audits = mock(IdentityAuditService.class);
    private final EmbeddingControlService control = mock(EmbeddingControlService.class);
    private final ParseDeadLetterService service = new ParseDeadLetterService(mapper, audits, control);
    private final ParseDeadLetterView receipt = new ParseDeadLetterView(9L, 7L, 11L, 0L, 3,
            "RETRYABLE", "MODEL_UNAVAILABLE", "依赖不可用", "REPLAYED", OffsetDateTime.now(), OffsetDateTime.now());

    @Test
    void invalidInputNeverTouchesDependencies() {
        for (String id : List.of("", " ", "a".repeat(257), "a\0b")) {
            badRequest(() -> service.replay(42, 9, id, "原因"));
            badRequest(() -> service.list(1, 20, null, null, null, null, 42, id));
        }
        for (String reason : List.of("", " ", "😀".repeat(2001), "a\0b")) {
            badRequest(() -> service.replay(42, 9, "id", reason));
        }
        badRequest(() -> service.replay(42, 9, null, "原因"));
        badRequest(() -> service.replay(42, 9, "id", null));
        badRequest(() -> service.replay(42, 0, "id", "原因"));
        verifyNoInteractions(mapper, audits, control);
    }

    @Test
    void preservesLongUnicodeReasonsAndStructuredAudit() throws Exception {
        String reason = "😀".repeat(300) + "\"\\\n\t正文";
        when(mapper.replay(9, "id", 42, reason)).thenReturn(1);
        when(mapper.findByReplayRequest("id")).thenReturn(receipt);
        assertThat(service.replay(42, 9, " id ", " " + reason + " ")).isSameAs(receipt);
        var order = inOrder(mapper, control, audits);
        order.verify(mapper).limitLockWait();
        order.verify(mapper).lockReplayRequest("parse-deadletter-replay:id");
        order.verify(mapper).findReplayBinding("id");
        order.verify(control).lockShared();
        order.verify(mapper).replay(9, "id", 42, reason);
        ArgumentCaptor<String> after = ArgumentCaptor.forClass(String.class);
        verify(audits).success(eq(AuditScope.PARSE_DEAD_LETTER_REPLAY), eq("id"), eq(42L), eq(9L),
                eq("REPLAY"), anyString(), after.capture());
        assertThat(new ObjectMapper().readTree(after.getValue()).path("reason").textValue()).isEqualTo(reason);
    }

    @Test
    void exactMaximumCodePointBoundariesAreAccepted() {
        String id = "a".repeat(256), reason = "😀".repeat(2000);
        when(mapper.findReplayBinding(id)).thenReturn(new ParseReplayBinding(9L, 42L, reason));
        when(mapper.findByReplayRequest(id)).thenReturn(receipt);
        assertThat(service.replay(42, 9, id, reason)).isSameAs(receipt);
        verifyNoInteractions(control, audits);
    }

    @Test
    void everyBindingFieldMustMatchIncludingLegacyRecords() {
        for (ParseReplayBinding binding : List.of(new ParseReplayBinding(8L, 42L, "原因"),
                new ParseReplayBinding(9L, 41L, "原因"), new ParseReplayBinding(9L, 42L, "其他"),
                new ParseReplayBinding(9L, null, null))) {
            when(mapper.findReplayBinding("id")).thenReturn(binding);
            assertThatThrownBy(() -> service.replay(42, 9, "id", "原因"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(409));
        }
        verifyNoInteractions(control, audits);
    }

    @Test
    void validatesEnumsPaginationAndTimeBeforeReadingData() {
        var now = OffsetDateTime.now();
        badRequest(() -> service.list(0, 20, null, null, null, null, 42, "id"));
        badRequest(() -> service.list(1, 101, null, null, null, null, 42, "id"));
        badRequest(() -> service.list(1, 20, "open", null, null, null, 42, "id"));
        badRequest(() -> service.list(1, 20, null, "unknown", null, null, 42, "id"));
        badRequest(() -> service.list(1, 20, null, null, now, now, 42, "id"));
        badRequest(() -> service.list(1, 20, null, null, now, now.minusDays(1), 42, "id"));
        verifyNoInteractions(mapper, audits, control);
    }

    @Test
    void passesOpenAndHalfOpenBoundsWithoutChangingOffsets() {
        var from = OffsetDateTime.parse("2026-10-20T08:00:00+08:00");
        var to = from.plusDays(1);
        service.list(2, 20, "REPLAYED", "PERMANENT", from, to, 42, "query");
        verify(mapper).count("REPLAYED", "PERMANENT", from, to);
        service.list(1, 20, "", "", null, null, 42, "all");
        verify(mapper).count(null, null, null, null);
    }

    @Test
    void auditAndLockFailuresAreNotReportedAsSuccess() {
        when(mapper.replay(9, "id", 42, "原因")).thenReturn(1);
        when(mapper.findByReplayRequest("id")).thenReturn(receipt);
        doThrow(new IllegalStateException("offline")).when(audits).success(eq(AuditScope.PARSE_DEAD_LETTER_REPLAY),
                eq("id"), eq(42L), eq(9L), eq("REPLAY"), anyString(), anyString());
        assertThatThrownBy(() -> service.replay(42, 9, "id", "原因")).isInstanceOf(IllegalStateException.class);
        when(mapper.lockReplayRequest("parse-deadletter-replay:blocked"))
                .thenThrow(new CannotAcquireLockException("busy"));
        assertThatThrownBy(() -> service.replay(42, 9, "blocked", "原因"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(503));
    }

    private static void badRequest(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.httpStatus()).isEqualTo(400));
    }
}
