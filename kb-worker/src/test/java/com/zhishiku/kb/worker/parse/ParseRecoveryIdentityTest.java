package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingControl;
import com.zhishiku.kb.worker.embedding.mapper.EmbeddingStateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseRecoveryIdentityTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TaskAlertOutbox alerts = mock(TaskAlertOutbox.class);
    private final EmbeddingStateMapper state = mock(EmbeddingStateMapper.class);
    private final ParseTaskRecoveryService recovery = new ParseTaskRecoveryService(jdbc, alerts, state);
    private final FailureClassifier.Classification failure = new FailureClassifier.Classification(
            FailureClassifier.FailureClass.RETRYABLE, "TIMEOUT", "timeout", false);

    @Test
    void allMaintenanceModesPreventLateCallbacksAndDeadLetters() {
        var task = ParseEmbeddingFixture.task();
        for (String mode : List.of("UNINITIALIZED", "QUIESCING", "REBUILDING", "VERIFYING", "BLOCKED")) {
            boolean initial = mode.equals("UNINITIALIZED");
            when(state.lockShared()).thenReturn(new EmbeddingControl(mode, null, null, initial ? null : "target",
                    initial ? null : "fp", 5, initial ? null : UUID.randomUUID()));
            assertThat(recovery.renewLease(task, "owner", 60)).isFalse();
            assertThat(recovery.scheduleRetry(task, "owner", failure, OffsetDateTime.now())).isFalse();
            assertThat(recovery.deadLetter(task, "owner", failure)).isFalse();
            recovery.recordLeaseLost(task, "owner");
            assertThat(recovery.sweepExhausted()).isZero();
        }
        verifyNoInteractions(jdbc, alerts);
    }

    @Test
    void failedUpdateDoesNotWriteVersionHistoryOrAlerts() {
        when(state.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        var task = ParseEmbeddingFixture.task();
        assertThat(recovery.scheduleRetry(task, "owner", failure, OffsetDateTime.now())).isFalse();
        assertThat(recovery.deadLetter(task, "owner", failure)).isFalse();
        verify(jdbc, never()).update(contains("kb_document_version"), any(Object[].class));
        verifyNoInteractions(alerts);
    }

    @Test
    void renewalAndFailureLockControlBeforeFencedUpdate() {
        when(state.lockShared()).thenReturn(ParseEmbeddingFixture.active());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        var task = ParseEmbeddingFixture.task();
        assertThat(recovery.renewLease(task, "owner", 60)).isTrue();
        var order = inOrder(state, jdbc);
        order.verify(state).lockShared();
        order.verify(jdbc).update(contains("AND claim_token = ? AND embedding_epoch = ?"),
                eq(60), eq(task.id()), eq("owner"), eq(9L), eq(4L), eq(task.embeddingProfileId()));
        assertThat(recovery.scheduleRetry(task, "owner", failure, OffsetDateTime.now())).isTrue();
        verify(jdbc).update(contains("INSERT INTO kb_parse_task_event"), any(Object[].class));
        verifyNoInteractions(alerts);
    }

    @Test
    void unknownControlFailsClosed() {
        assertThatThrownBy(() -> recovery.renewLease(ParseEmbeddingFixture.task(), "owner", 60))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc);
    }
}
