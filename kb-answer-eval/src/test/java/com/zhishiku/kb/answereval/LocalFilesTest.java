package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFilesTest {
    @TempDir
    Path root;

    @Test
    void readsAtLimitAndRejectsActualExtraByte() throws IOException {
        assertThat(StrictJson.bounded(new ByteArrayInputStream(new byte[8]), 8, () -> { })).hasSize(8);
        assertThatThrownBy(() -> StrictJson.bounded(new ByteArrayInputStream(new byte[9]), 8, () -> { }))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR LIMIT");
        Path file = Files.write(root.resolve("input"), new byte[9]);
        assertThatThrownBy(() -> LocalFiles.read(file, 8)).isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void frozenBytesCannotBeModifiedByCallerOrLaterFileChanges() throws IOException {
        Path file = Files.writeString(root.resolve("input"), "first");
        LocalFiles.Frozen frozen = LocalFiles.read(file, 8);
        frozen.bytes()[0] = 0;
        Files.writeString(file, "second");
        assertThat(new String(frozen.bytes(), StandardCharsets.UTF_8)).isEqualTo("first");
        assertThat(frozen.sha256()).isEqualTo(StrictJson.textSha("first"));
    }

    @Test
    void boundsJsonDepthLineBytesAndRows() {
        String deep = "{\"a\":".repeat(33) + "1" + "}".repeat(33);
        assertThatThrownBy(() -> StrictJson.parse(deep.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EvaluationFailure.class);
        byte[] oversized = ("{\"x\":\"" + "x".repeat(StrictJson.LINE_LIMIT) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StrictJson.lines(oversized, DataModel.Case.class, 1))
                .isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR LIMIT");
        assertThatThrownBy(() -> StrictJson.lines(StrictJson.jsonl(List.of(DataFixtures.item(), DataFixtures.item())),
                DataModel.Case.class, 1)).isInstanceOf(EvaluationFailure.class).hasMessage("ANSWER_EVAL_ERROR LIMIT");
    }

    @Test
    void rejectsNetworkPathsUrisWildcardsAndTraversal() {
        for (String path : List.of("//server/share/input", "https://example.invalid/input", "*.json", "input?x")) {
            assertThatThrownBy(() -> LocalFiles.path(path)).isInstanceOf(EvaluationFailure.class);
        }
        assertThatThrownBy(() -> LocalFiles.child(root, "../outside")).isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> LocalFiles.child(root, "data:stream")).isInstanceOf(EvaluationFailure.class);
    }
}
