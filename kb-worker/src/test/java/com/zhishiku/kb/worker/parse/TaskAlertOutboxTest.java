package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskAlertOutboxTest {

    @Test
    void enqueueUsesDedupeAndDispatchCallsAdapterWithBoundedTask() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskFailureAlert alert = mock(TaskFailureAlert.class);
        TaskAlertOutbox outbox = new TaskAlertOutbox(jdbc, alert);
        ParseTask task = new ParseTask(7L, 11L, 2, 3, OffsetDateTime.now(), "worker",
                "secret/object/key", "file.pdf", 1L, "application/pdf");
        FailureClassifier.Classification classification = new FailureClassifier.Classification(
                FailureClassifier.FailureClass.PERMANENT, "PARSER_ERROR", "parser failed", false);

        outbox.enqueue(task, "DEAD_LETTERED", classification, "dead-letter:7:0", true);
        verify(jdbc).update(contains("ON CONFLICT (dedupe_key) DO NOTHING"),
                eq(7L), eq(11L), eq(0L), eq("DEAD_LETTERED"), eq("PERMANENT"),
                eq("PARSER_ERROR"), eq("parser failed"), eq("dead-letter:7:0"));

        doReturn(List.of(new TaskAlertOutbox.Delivery(3L, 7L, 11L, 0L, "DEAD_LETTERED",
                "PERMANENT", "PARSER_ERROR", "parser failed")))
                .when(jdbc).query(anyString(), any(RowMapper.class));
        outbox.dispatch();
        verify(alert).onFailure(any(ParseTask.class), eq("parser failed"), eq(true));
        verify(jdbc).update(contains("SET status = 'SENT'"), eq(3L));
    }
}
