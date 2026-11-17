package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OutputPublisherTest {
    @TempDir
    Path root;

    @Test
    void publishesCompleteNewDirectoryWithoutOverwriting() throws IOException {
        OutputPublisher publisher = new OutputPublisher();
        Path target = root.resolve("result");
        try (var session = publisher.begin(publisher.preflight(target.toString()))) {
            session.put("inputs/input.json", new byte[]{1, 2, 3});
            assertThat(target).doesNotExist();
            session.finish();
        }
        assertThat(target.resolve("complete.json")).exists();
        assertThat(Files.readAllBytes(target.resolve("inputs/input.json"))).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> publisher.preflight(target.toString())).isInstanceOf(EvaluationFailure.class);
    }

    @Test
    void concurrentTargetIsPreservedAndOnlyOwnedStagingIsCleaned() throws IOException {
        Path target = root.resolve("result");
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void move(Path staging, Path destination) throws IOException {
                Files.createDirectory(destination);
                Files.writeString(destination.resolve("other"), "keep");
                super.move(staging, destination);
            }
        };
        assertThatThrownBy(() -> {
            try (var session = publisher.begin(publisher.preflight(target.toString()))) {
                session.put("value.json", new byte[]{1});
                session.finish();
            }
        }).isInstanceOf(EvaluationFailure.class);
        assertThat(Files.readString(target.resolve("other"))).isEqualTo("keep");
        try (var children = Files.list(root)) {
            assertThat(children.toList()).containsExactly(target);
        }
    }

    @Test
    void failedPartialWriteLeavesNoCompletedRunOrDeletesOtherFiles() throws IOException {
        Path sentinel = Files.writeString(root.resolve("keep"), "keep");
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void write(OutputStream stream, byte[] bytes) throws IOException {
                stream.write(1);
                throw new IOException("sensitive remote body");
            }
        };
        assertThatThrownBy(() -> {
            try (var session = publisher.begin(publisher.preflight(root.resolve("result").toString()))) {
                session.put("value.json", new byte[]{1, 2});
            }
        }).isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("sensitive");
        try (var children = Files.list(root)) {
            assertThat(children.toList()).containsExactly(sentinel);
        }
    }

    @Test
    void rejectsLinkOutsideRootIncludingAfterPreflight() throws IOException {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "环境不支持创建符号链接");
        }
        OutputPublisher publisher = new OutputPublisher();
        assertThatThrownBy(() -> publisher.preflight(link.resolve("result").toString())).isInstanceOf(EvaluationFailure.class);
        Path parent = Files.createDirectory(root.resolve("parent"));
        var target = publisher.preflight(parent.resolve("result").toString());
        Files.delete(parent);
        Files.createSymbolicLink(parent, outside);
        assertThatThrownBy(() -> publisher.begin(target)).isInstanceOf(EvaluationFailure.class);
        assertThat(outside.resolve("result")).doesNotExist();
    }

    @Test
    void caughtPartialWritePermanentlyPreventsPublication() throws IOException {
        Path target = root.resolve("result");
        var publisher = new OutputPublisher() {
            private int writes;

            @Override
            protected void write(OutputStream stream, byte[] bytes) throws IOException {
                stream.write(bytes, 0, 1);
                if (++writes == 2) {
                    throw new IOException("SECRET");
                }
                stream.write(bytes, 1, bytes.length - 1);
            }
        };
        try (var session = publisher.begin(publisher.preflight(target.toString()))) {
            session.put("first.json", new byte[]{1, 2});
            assertThatThrownBy(() -> session.put("inputs/partial.json", new byte[]{3, 4}))
                    .isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("SECRET");
            assertThatThrownBy(() -> session.put("status.json", new byte[]{5}))
                    .isInstanceOf(EvaluationFailure.class);
            assertThatThrownBy(session::finish).isInstanceOf(EvaluationFailure.class);
        }
        assertThat(target).doesNotExist();
        try (var children = Files.list(root)) {
            assertThat(children.toList()).isEmpty();
        }
    }
}
