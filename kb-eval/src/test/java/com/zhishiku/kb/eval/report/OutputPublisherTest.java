package com.zhishiku.kb.eval.report;

import com.zhishiku.kb.eval.cli.ExitCodes;
import com.zhishiku.kb.eval.input.EvaluationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputPublisherTest {
    @TempDir
    Path parent;
    private final OutputPublisher publisher = new OutputPublisher();

    @Test
    void publishesOnlyAfterWriterCompletesAndLeavesInputUntouched() throws Exception {
        Path input = Files.writeString(parent.resolve("input.json"), "input-sentinel");
        Path target = parent.resolve("report");
        publisher.publish(target, staging -> {
            assertThat(staging.getParent()).isEqualTo(parent);
            assertThat(Files.exists(target)).isFalse();
            Files.writeString(staging.resolve("summary.json"), "complete");
        });
        assertThat(Files.readString(target.resolve("summary.json"))).isEqualTo("complete");
        assertThat(Files.readString(input)).isEqualTo("input-sentinel");
        assertNoStaging();
    }

    @Test
    void existingOutputIsNotTouchedAndWriterIsNotCalled() throws Exception {
        Path target = Files.createDirectory(parent.resolve("report"));
        Files.writeString(target.resolve("keep"), "existing");
        assertThatThrownBy(() -> publisher.publish(target, staging -> {
            throw new AssertionError("Must not run");
        }))
                .isInstanceOf(EvaluationException.class).hasMessage("OUTPUT_EXISTS");
        assertThat(Files.readString(target.resolve("keep"))).isEqualTo("existing");
    }

    @Test
    void raceCreatedTargetIsNotOverwrittenAndOnlyOwnStagingIsCleaned() throws Exception {
        Path target = parent.resolve("report");
        Path unrelated = Files.createDirectory(parent.resolve(".kb-eval-unrelated"));
        Files.writeString(unrelated.resolve("keep"), "unrelated");
        assertThatThrownBy(() -> publisher.publish(target, staging -> {
            Files.writeString(staging.resolve("part"), "partial");
            Files.createDirectory(target);
            Files.writeString(target.resolve("keep"), "racer");
        })).hasMessage("OUTPUT_EXISTS");
        assertThat(Files.readString(target.resolve("keep"))).isEqualTo("racer");
        assertThat(Files.readString(unrelated.resolve("keep"))).isEqualTo("unrelated");
        try (var entries = Files.list(parent)) {
            assertThat(entries.filter(path -> path.getFileName().toString().startsWith(".kb-eval-")).toList()).containsExactly(unrelated);
        }
    }

    @Test
    void writeFailureCleansNestedTemporaryFilesAndDoesNotPublish() throws Exception {
        Path target = parent.resolve("report");
        assertThatThrownBy(() -> publisher.publish(target, staging -> {
            Files.createDirectory(staging.resolve("nested"));
            Files.writeString(staging.resolve("nested/part"), "partial");
            throw new IOException("SENSITIVE_TOKEN");
        })).isInstanceOf(EvaluationException.class).hasMessage("OUTPUT_FAILED");
        assertThat(ExitCodes.forError()).isEqualTo(2);
        assertThat(Files.exists(target)).isFalse();
        assertNoStaging();
    }

    @Test
    void invalidOutputLocationsAreRejected() {
        assertThatThrownBy(() -> OutputPublisher.outputPath("https://localhost/report")).hasMessage(
                "LOCAL_FILE_REQUIRED");
        assertThatThrownBy(() -> OutputPublisher.outputPath("\\\\server\\share\\report")).hasMessage(
                "LOCAL_FILE_REQUIRED");
        assertThatThrownBy(() -> OutputPublisher.outputPath("\\/server/share/report")).hasMessage(
                "LOCAL_FILE_REQUIRED");
        assertThatThrownBy(() -> OutputPublisher.outputPath("/\\server/share/report")).hasMessage(
                "LOCAL_FILE_REQUIRED");
    }

    private void assertNoStaging() throws Exception {
        try (var entries = Files.list(parent)) {
            assertThat(entries.filter(path -> path.getFileName().toString().startsWith(".kb-eval-")).toList()).isEmpty();
        }
    }
}
