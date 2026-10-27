package com.zhishiku.kb.app.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zhishiku.kb.app.document.model.DocumentProgress;
import com.zhishiku.kb.app.document.model.DocumentVersionView;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRecoveryJsonTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void progressAndVersionExposeRecoveryFactsWithoutObjectKey() throws Exception {
        DocumentProgress progress = new DocumentProgress();
        progress.setTaskStatus("pending");
        progress.setWaitingReason("EMBEDDING_MAINTENANCE");
        progress.setFailureClass("RETRYABLE");
        progress.setFailureCode("RETRY_EXHAUSTED");
        progress.setExecutionGeneration(2L);
        progress.setDeadLetterStatus("OPEN");
        progress.setNextAttemptAt(OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

        DocumentVersionView version = new DocumentVersionView();
        version.setTaskStatus("failed");
        version.setFailureSummary("依赖超时");
        version.setExecutionGeneration(2L);
        version.setDeadLetterStatus("OPEN");

        String progressJson = json.writeValueAsString(progress);
        String versionJson = json.writeValueAsString(version);
        assertThat(progressJson).contains("executionGeneration", "deadLetterStatus", "nextAttemptAt",
                        "EMBEDDING_MAINTENANCE")
                .doesNotContain("objectKey");
        assertThat(versionJson).contains("executionGeneration", "deadLetterStatus", "failureSummary")
                .doesNotContain("objectKey");
    }
}
