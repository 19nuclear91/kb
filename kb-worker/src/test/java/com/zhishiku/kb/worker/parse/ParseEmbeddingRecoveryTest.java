package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.common.embedding.EmbeddingProtocolFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseEmbeddingRecoveryTest {
    @ParameterizedTest
    @EnumSource(WorkerEmbeddingFailure.Reason.class)
    void boundedReasonsSelectOneRecoveryWithoutPartialPublish(WorkerEmbeddingFailure.Reason reason) throws Exception {
        var failure = new WorkerEmbeddingFailure(reason);
        var claims = mock(ParseTaskClaimService.class);
        var recovery = mock(ParseTaskRecoveryService.class);
        var publisher = mock(ChunkPublisher.class);
        var task = ParseEmbeddingFixture.task();
        when(claims.claim(anyString(), anyInt(), eq(1))).thenReturn(List.of(task));
        ParsePipeline pipeline = (input, lease) -> {
            // 超时是本地终结，租约事实仍然为真。
            ((WorkerExecution) lease.budget()).stop(reason);
            assertThat(lease.stillHeld()).isTrue();
            throw failure;
        };
        var runner = new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, publisher,
                new ParseProperties(60, 1, 100, 1000, 10), mock(TaskFailureAlert.class),
                new FailureClassifier(), recovery, claims);
        try {
            ParseRunnerTestSupport.run(runner);
            var classification = ArgumentCaptor.forClass(FailureClassifier.Classification.class);
            if (failure.controlStop()) {
                verifyNoInteractions(recovery);
            } else {
                if (failure.retryable()) {
                    verify(recovery).scheduleRetry(eq(task), anyString(), classification.capture(), any());
                    verify(recovery, never()).deadLetter(any(), anyString(), any());
                } else {
                    verify(recovery).deadLetter(eq(task), anyString(), classification.capture());
                    verify(recovery, never()).scheduleRetry(any(), anyString(), any(), any());
                }
                assertThat(classification.getValue().code()).isEqualTo(failure.parseCode());
                assertThat(classification.getValue().summary()).isEqualTo("Worker embedding: " + reason.name());
            }
            verifyNoInteractions(publisher);
        } finally {
            runner.shutdownPipelineExecutor();
        }
    }

    @Test
    void protocolFailureCannotFallBackToUnknownOrExposeItsMessage() {
        var classified = new FailureClassifier().classify(new EmbeddingProtocolFailure());
        assertThat(classified.permanent()).isTrue();
        assertThat(classified.code()).isEqualTo("EMBEDDING_INVALID_RESPONSE");
        assertThat(classified.summary()).isEqualTo("Worker embedding: INVALID_RESPONSE");
    }
}
