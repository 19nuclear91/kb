package com.zhishiku.kb.ai.evaluation;

import com.zhishiku.kb.ai.conversation.BeginResult;
import com.zhishiku.kb.ai.conversation.ExecutionKey;
import com.zhishiku.kb.answereval.DataModel;
import com.zhishiku.kb.answereval.OutputPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureRegistryTest {
    @TempDir
    Path root;

    @Test
    void disabledAndOrdinaryRequestsNeverCreateDirectories() {
        Path absent = root.resolve("absent");
        try (var disabled = new CaptureRegistry(new EvaluationProperties(false, null, Set.of(), absent));
             var enabled = registry()) {
            assertThat(disabled.claim(CaptureFixtures.begin(1), "PRIVATE_QUESTION")).isNull();
            assertThat(enabled.claim(CaptureFixtures.begin(1), "PRIVATE_QUESTION")).isNull();
            assertThat(absent).doesNotExist();
            assertThat(root.resolve("claims")).doesNotExist();
            assertThat(root.resolve("captures")).doesNotExist();
        }
    }

    @Test
    void matchesTrustedIdentityQuestionFirstTurnAndExpiry() throws IOException {
        CaptureFixtures.registration(root, 9);
        var begin = CaptureFixtures.begin(9);
        try (var registry = registry()) {
            assertThat(registry.claim(begin, "changed")).isNull();
            assertThat(registry.claim(new BeginResult(9, begin.turnId(), 2, "RUNNING", false, begin.execution()),
                    CaptureFixtures.QUESTION)).isNull();
            assertThat(registry.claim(new BeginResult(9, begin.turnId(), 1, "COMPLETED", true, null),
                    CaptureFixtures.QUESTION)).isNull();
            var other = new ExecutionKey(9, begin.turnId(), 2, 0, 1, "ticket");
            assertThat(registry.claim(new BeginResult(9, begin.turnId(), 1, "RUNNING", false, other),
                    CaptureFixtures.QUESTION)).isNull();
            assertThat(registry.claim(CaptureFixtures.begin(10), CaptureFixtures.QUESTION)).isNull();
        }
        try (var expired = new CaptureRegistry(CaptureFixtures.properties(root),
                Clock.offset(CaptureFixtures.CLOCK, Duration.ofSeconds(61)), new OutputPublisher(), Runnable::run)) {
            assertThat(expired.claim(begin, CaptureFixtures.QUESTION)).isNull();
        }
    }

    @Test
    void concurrentClaimsHaveOneWinnerAndCannotBeReusedAfterRestart() throws Exception {
        CaptureFixtures.registration(root, 9);
        var begin = CaptureFixtures.begin(9);
        try (var registry = registry(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> registry.claim(begin, CaptureFixtures.QUESTION));
            var second = executor.submit(() -> registry.claim(begin, CaptureFixtures.QUESTION));
            List<CaptureSession> winners = java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(winners).hasSize(1);
            winners.getFirst().finish();
        }
        try (var restarted = registry()) {
            assertThat(restarted.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION)).isNull();
        }
    }

    @Test
    void registrationCannotEscapeSharedRoot() throws IOException {
        var original = CaptureFixtures.registration(root, 9);
        var escaped = new DataModel.Registration(1, original.runId(), original.caseId(), original.attemptId(),
                "../outside/dataset.json", original.datasetSha256(), "test", "instance", 1, 9, 0,
                original.questionSha256(), original.expiresAtEpochMillis(), original.artifactKey());
        CaptureFixtures.writeRegistration(root, escaped);
        try (var registry = registry()) {
            assertThat(registry.claim(CaptureFixtures.begin(9), CaptureFixtures.QUESTION)).isNull();
        }
        assertThat(root.resolve("claims")).doesNotExist();
    }

    private CaptureRegistry registry() {
        return new CaptureRegistry(CaptureFixtures.properties(root), CaptureFixtures.CLOCK, new OutputPublisher(), Runnable::run);
    }
}
