package com.zhishiku.kb.app.parse;

import com.zhishiku.kb.app.auth.service.AuditScope;
import com.zhishiku.kb.app.auth.service.IdentityAuditService;
import com.zhishiku.kb.app.embedding.service.EmbeddingControlService;
import com.zhishiku.kb.app.parse.mapper.ParseDeadLetterMapper;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterPage;
import com.zhishiku.kb.app.parse.model.ParseDeadLetterView;
import com.zhishiku.kb.app.parse.model.ParseReplayBinding;
import com.zhishiku.kb.app.parse.service.ParseDeadLetterService;
import com.zhishiku.kb.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseDeadLetterServiceTest {

    private final ParseDeadLetterMapper mapper = mock(ParseDeadLetterMapper.class);
    private final IdentityAuditService audits = mock(IdentityAuditService.class);
    private final EmbeddingControlService control =
            mock(EmbeddingControlService.class);
    private final ParseDeadLetterService service = new ParseDeadLetterService(mapper, audits, control);
    private final ParseDeadLetterView view = new ParseDeadLetterView(9L, 7L, 11L, 0L, 3,
            "RETRYABLE", "RETRY_EXHAUSTED", "依赖超时", "REPLAYED",
            OffsetDateTime.now(), null);

    @Test
    void replayRequiresRequestAndIsIdempotent() {
        when(mapper.findByReplayRequest("request-1")).thenReturn(view);
        when(mapper.findReplayBinding("request-1")).thenReturn(new ParseReplayBinding(9L, 42L, "已恢复"));

        ParseDeadLetterView result = service.replay(42L, 9L, "request-1", "已恢复");

        assertThat(result).isSameAs(view);
        verify(mapper, never()).replay(anyLong(), anyString(), anyLong(), anyString());
        verifyNoInteractions(audits);
    }

    @Test
    void replayCreatesOneAuditedTransitionAndRejectsMissingTarget() {
        when(mapper.findByReplayRequest("request-2")).thenReturn(view);
        when(mapper.replay(9L, "request-2", 42L, "对象存储已恢复")).thenReturn(1);

        ParseDeadLetterView result = service.replay(42L, 9L, "request-2", "对象存储已恢复");

        assertThat(result).isSameAs(view);
        verify(audits).success(eq(AuditScope.PARSE_DEAD_LETTER_REPLAY), eq("request-2"), eq(42L),
                eq(9L), eq("REPLAY"), anyString(), anyString());
        var order = inOrder(control, mapper);
        order.verify(control).lockShared();
        order.verify(mapper).replay(9L, "request-2", 42L, "对象存储已恢复");

        when(mapper.findByReplayRequest("request-3")).thenReturn(null);
        when(mapper.replay(99L, "request-3", 42L, "无效")).thenReturn(0);
        assertThatThrownBy(() -> service.replay(42L, 99L, "request-3", "无效"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void listAuditsQueryAndReturnsBoundedPage() {
        when(mapper.count("OPEN", "RETRYABLE", null, null)).thenReturn(1L);
        when(mapper.list(eq("OPEN"), eq("RETRYABLE"), isNull(), isNull(), eq(0L), eq(20)))
                .thenReturn(List.of(view));

        ParseDeadLetterPage result = service.list(1, 20, "OPEN", "RETRYABLE",
                null, null, 42L, "query-1");

        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getItems()).containsExactly(view);
        verify(audits).success(eq(AuditScope.PARSE_DEAD_LETTER_QUERY), eq("query-1"), eq(42L),
                isNull(), eq("LIST"), anyString(), anyString());
    }
}
