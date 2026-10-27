package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.parse.model.ParseTask;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseTaskRunnerRecoveryTest {

    @Test
    void lateHeartbeatOnlyInvalidatesItsOwnExecution() throws Exception {
        var mapper = mock(ParseTaskMapper.class);
        var pipeline = mock(ParsePipeline.class);
        var publisher = mock(ChunkPublisher.class);
        var recovery = mock(ParseTaskRecoveryService.class);
        var claims = mock(ParseTaskClaimService.class);
        var firstStarted = new CountDownLatch(1);
        var firstFinish = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondFinish = new CountDownLatch(1);
        var heartbeatStarted = new CountDownLatch(1);
        var heartbeatFinish = new CountDownLatch(1);
        var first = ParseEmbeddingFixture.task();
        var second = ParseEmbeddingFixture.task(3L, 4L, "key", "b.txt", 100, "text/plain");
        when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(pipeline.process(eq(first), any())).thenAnswer(call -> {
            firstStarted.countDown();
            Assertions.assertThat(firstFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return new ParsePipeline.ParsedDocument(List.of());
        });
        when(pipeline.process(eq(second), any())).thenAnswer(call -> {
            secondStarted.countDown();
            Assertions.assertThat(secondFinish.await(5, TimeUnit.SECONDS)).isTrue();
            Assertions.assertThat(((ParsePipeline.LeaseHandle) call.getArgument(1)).stillHeld()).isTrue();
            return new ParsePipeline.ParsedDocument(List.of());
        });
        when(recovery.renewLease(eq(first), anyString(), anyInt())).thenAnswer(call -> {
            heartbeatStarted.countDown();
            Assertions.assertThat(heartbeatFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return false;
        });
        var runner = new ParseTaskRunner(mapper, pipeline, publisher, properties(), mock(TaskFailureAlert.class),
                mock(FailureClassifier.class), recovery, claims);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var poll = executor.submit(runner::poll);
            Assertions.assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var heartbeat = executor.submit(runner::heartbeat);
            Assertions.assertThat(heartbeatStarted.await(5, TimeUnit.SECONDS)).isTrue();
            firstFinish.countDown();
            Assertions.assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            heartbeatFinish.countDown();
            heartbeat.get(5, TimeUnit.SECONDS);
            secondFinish.countDown();
            poll.get(5, TimeUnit.SECONDS);
            verify(publisher).publish(eq(second), anyString(), any(ParsePipeline.ParsedDocument.class));
        } finally {
            firstFinish.countDown();
            secondFinish.countDown();
            heartbeatFinish.countDown();
            executor.shutdownNow();
            runner.shutdownPipelineExecutor();
        }
    }

    private final ParseTask task = new ParseTask(
            7L, 11L, 1, 3, OffsetDateTime.now().plusMinutes(5), "worker",
            "object-key", "file.pdf", 12L, "application/pdf");

    @Test
    void permanentFailureIsDeadLetteredWithoutRetry() throws Exception {
        ParseTaskMapper mapper = mock(ParseTaskMapper.class);
        ParsePipeline pipeline = mock(ParsePipeline.class);
        ChunkPublisher publisher = mock(ChunkPublisher.class);
        TaskFailureAlert alert = mock(TaskFailureAlert.class);
        FailureClassifier classifier = mock(FailureClassifier.class);
        ParseTaskRecoveryService recovery = mock(ParseTaskRecoveryService.class);

        ParseTaskClaimService claims = mock(ParseTaskClaimService.class);
        when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(task));
        doThrow(new IllegalArgumentException("bad input")).when(pipeline).process(any(), any());
        FailureClassifier.Classification permanent = new FailureClassifier.Classification(
                FailureClassifier.FailureClass.PERMANENT, "INVALID_INPUT", "invalid input", false);
        when(classifier.classify(any())).thenReturn(permanent);
        when(recovery.deadLetter(eq(task), anyString(), eq(permanent))).thenReturn(true);

        ParseTaskRunner runner = new ParseTaskRunner(mapper, pipeline, publisher,
                properties(), alert, classifier, recovery, claims);
        try {
            runner.poll();
        } finally {
            runner.shutdownPipelineExecutor();
        }

        verify(recovery).deadLetter(eq(task), anyString(), eq(permanent));
        verify(recovery, never()).scheduleRetry(any(), anyString(), any(), any());
        verifyNoInteractions(alert);
    }

    @Test
    void retryableFailureSchedulesBackoffAndDoesNotDeadLetter() throws Exception {
        ParseTaskMapper mapper = mock(ParseTaskMapper.class);
        ParsePipeline pipeline = mock(ParsePipeline.class);
        ChunkPublisher publisher = mock(ChunkPublisher.class);
        TaskFailureAlert alert = mock(TaskFailureAlert.class);
        FailureClassifier classifier = mock(FailureClassifier.class);
        ParseTaskRecoveryService recovery = mock(ParseTaskRecoveryService.class);

        ParseTaskClaimService claims = mock(ParseTaskClaimService.class);
        when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(task));
        doThrow(new RuntimeException("object store timeout")).when(pipeline).process(any(), any());
        FailureClassifier.Classification retryable = new FailureClassifier.Classification(
                FailureClassifier.FailureClass.RETRYABLE, "OBJECT_STORAGE_UNAVAILABLE",
                "object store timeout", false);
        when(classifier.classify(any())).thenReturn(retryable);
        when(recovery.scheduleRetry(eq(task), anyString(), eq(retryable), any())).thenReturn(true);

        ParseTaskRunner runner = new ParseTaskRunner(mapper, pipeline, publisher,
                properties(), alert, classifier, recovery, claims);
        try {
            runner.poll();
        } finally {
            runner.shutdownPipelineExecutor();
        }

        verify(recovery).scheduleRetry(eq(task), anyString(), eq(retryable), any());
        verify(recovery, never()).deadLetter(any(), anyString(), any());
        verifyNoInteractions(alert);
    }

    @Test
    void leaseLostFailureDoesNotWriteRecoveryState() throws Exception {
        ParseTaskMapper mapper = mock(ParseTaskMapper.class);
        ParsePipeline pipeline = mock(ParsePipeline.class);
        ChunkPublisher publisher = mock(ChunkPublisher.class);
        TaskFailureAlert alert = mock(TaskFailureAlert.class);
        FailureClassifier classifier = mock(FailureClassifier.class);
        ParseTaskRecoveryService recovery = mock(ParseTaskRecoveryService.class);

        ParseTaskClaimService claims = mock(ParseTaskClaimService.class);
        when(claims.claim(anyString(), anyInt(), anyInt())).thenReturn(List.of(task));
        doThrow(new LeaseLostException()).when(pipeline).process(any(), any());
        FailureClassifier.Classification leaseLost = new FailureClassifier.Classification(
                null, "LEASE_LOST", "解析任务租约已失守", true);
        when(classifier.classify(any())).thenReturn(leaseLost);

        ParseTaskRunner runner = new ParseTaskRunner(mapper, pipeline, publisher,
                properties(), alert, classifier, recovery, claims);
        try {
            runner.poll();
        } finally {
            runner.shutdownPipelineExecutor();
        }

        verify(recovery).recordLeaseLost(eq(task), anyString());
        verifyNoInteractions(alert);
    }

    private static ParseProperties properties() {
        return new ParseProperties(60, 1, 100, 1_000, 10);
    }
}
