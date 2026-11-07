package com.zhishiku.kb.worker.parse;

import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingFailure;
import com.zhishiku.kb.worker.embedding.execution.WorkerEmbeddingSettings;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecution;
import com.zhishiku.kb.worker.embedding.execution.WorkerExecutions;
import com.zhishiku.kb.worker.parse.mapper.ParseTaskMapper;
import com.zhishiku.kb.worker.telemetry.TaskContextPropagation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParseTaskAdmissionTest {
    @Test
    void busyBusinessLanePreventsClaimAndConfiguredBatchCannotOverClaim() throws Exception {
        var claims = mock(ParseTaskClaimService.class);
        var finish = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var blocker = lanes.submit(WorkerExecution.Purpose.REBUILD, execution -> {
                execution.begin(Duration.ofMinutes(1));
                started.countDown();
                await(finish);
                execution.succeed();
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var runner = runner(claims, mock(ParsePipeline.class), mock(ChunkPublisher.class), lanes);
            assertThat(runner.poll()).isNull();
            verifyNoInteractions(claims);
            finish.countDown();
            blocker.exited().get(5, TimeUnit.SECONDS);
            when(claims.claim(anyString(), anyInt(), eq(1))).thenReturn(List.of());
            ParseRunnerTestSupport.run(runner);
            verify(claims).claim(anyString(), anyInt(), eq(1));
        } finally {
            finish.countDown();
        }
    }

    @Test
    void cancelledButUnreturnedPipelineDoesNotPublishOrClaimAgain() throws Exception {
        var claims = mock(ParseTaskClaimService.class);
        var publisher = mock(ChunkPublisher.class);
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(claims.claim(anyString(), anyInt(), eq(1))).thenReturn(List.of(ParseEmbeddingFixture.task()));
        ParsePipeline pipeline = (task, lease) -> {
            started.countDown();
            awaitIgnoringInterrupt(finish);
            return new ParsePipeline.ParsedDocument(List.of());
        };
        try (var lanes = new WorkerExecutions(WorkerEmbeddingSettings.defaults())) {
            var runner = runner(claims, pipeline, publisher, lanes);
            var ticket = ParseRunnerTestSupport.submit(runner);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            ticket.execution().stop(WorkerEmbeddingFailure.Reason.TIMEOUT);
            assertThat(runner.poll()).isNull();
            verify(claims, times(1)).claim(anyString(), anyInt(), eq(1));
            assertThat(ticket.exited()).isNotDone();
            finish.countDown();
            ticket.exited().get(5, TimeUnit.SECONDS);
            verifyNoInteractions(publisher);
        } finally {
            finish.countDown();
        }
    }

    private static ParseTaskRunner runner(ParseTaskClaimService claims, ParsePipeline pipeline,
                                          ChunkPublisher publisher, WorkerExecutions lanes) {
        return new ParseTaskRunner(mock(ParseTaskMapper.class), pipeline, publisher,
                new ParseProperties(60, 100, 100, 1000, 10), mock(TaskFailureAlert.class),
                new FailureClassifier(), mock(ParseTaskRecoveryService.class), new ParseTaskMetrics(), claims,
                new TaskContextPropagation(ObservationRegistry.NOOP, Tracer.NOOP), lanes);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5)) {
            try {
                if (latch.await(5, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException ignored) {
                // 模拟不响应中断的流水线。
            }
        }
        throw new AssertionError("barrier timed out");
    }
}
