package com.zhishiku.kb.eval.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputPublisherTest {
    @TempDir
    Path root;
    final Fixtures.FakeTransport transport = new Fixtures.FakeTransport();
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    int run(OutputPublisher publisher) {
        return CollectionCli.execute(Fixtures.args(root), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), Fixtures.collector(transport, new AtomicLong(), publisher));
    }

    void successResponses() {
        transport.add(Fixtures.me(7)).add(Fixtures.empty());
    }

    @Test
    void existingTargetIsRejectedBeforeNetworkWithoutTouchingInputs() throws IOException {
        Fixtures.files(root, "a");
        Path target = Files.createDirectory(root.resolve("output"));
        Files.writeString(target.resolve("keep"), "preserved");
        byte[] original = Files.readAllBytes(root.resolve("dataset.json"));
        assertThat(run(new OutputPublisher())).isEqualTo(2);
        assertThat(transport.requests).isEmpty();
        assertThat(Files.readString(target.resolve("keep"))).isEqualTo("preserved");
        assertThat(Files.readAllBytes(root.resolve("dataset.json"))).isEqualTo(original);
        assertThat(stdout.size()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 300})
    void overlongTargetNameIsRejectedBeforeClientCreation(int length) throws IOException {
        Fixtures.files(root, "a");
        assertPreflightFailure(root.resolve("x".repeat(length)), new OutputPublisher());
    }

    @Test
    void overlongTargetUnderMissingParentIsRejectedBeforeClientCreation() throws IOException {
        Fixtures.files(root, "a");
        assertPreflightFailure(root.resolve("new-parent").resolve("x".repeat(300)), new OutputPublisher());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attributeProbeErrorsAreNotTreatedAsMissingTargets(boolean accessDenied) throws IOException {
        Fixtures.files(root, "a");
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected BasicFileAttributes attributes(Path path) throws IOException {
                if (accessDenied) {
                    throw new AccessDeniedException(Fixtures.SECRET);
                }
                throw new IOException(Fixtures.SECRET);
            }
        };
        assertPreflightFailure(root.resolve("output"), publisher);
    }

    @Test
    void valid255CharacterTargetNameStillPublishes() throws IOException {
        Fixtures.files(root, "a");
        successResponses();
        Path output = root.resolve("x".repeat(255));
        String[] args = Fixtures.args(root);
        args[7] = output.toString();
        int exit = CollectionCli.execute(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Fixtures.collector(transport, new AtomicLong(), new OutputPublisher()));
        assertThat(exit).isZero();
        assertThat(output.resolve("run.json")).exists();
        assertThat(output.resolve("results.jsonl")).exists();
        assertThat(output.resolve("collection.json")).exists();
        assertThat(transport.requests).hasSize(2);
        assertThat(stderr.size()).isZero();
        noStaging();
    }

    private void assertPreflightFailure(Path output, OutputPublisher publisher) throws IOException {
        byte[] originalDataset = Files.readAllBytes(root.resolve("dataset.json"));
        byte[] originalLabels = Files.readAllBytes(root.resolve("labels.jsonl"));
        Path existing = Files.createDirectory(root.resolve("existing-run"));
        Files.writeString(existing.resolve("keep"), "preserved");
        String[] args = Fixtures.args(root);
        args[7] = output.toString();
        AtomicInteger clients = new AtomicInteger();
        Collector collector = new Collector(name -> Fixtures.SECRET, config -> {
            clients.incrementAndGet();
            return transport;
        }, () -> 0, Fixtures.UTC, publisher);
        int exit = CollectionCli.execute(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), collector);
        assertThat(exit).isEqualTo(2);
        assertThat(clients.get()).isZero();
        assertThat(transport.requests).isEmpty();
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .isEqualTo("COLLECT_ERROR OUTPUT role=output line=0 field=file" + System.lineSeparator());
        assertThat(Files.readAllBytes(root.resolve("dataset.json"))).isEqualTo(originalDataset);
        assertThat(Files.readAllBytes(root.resolve("labels.jsonl"))).isEqualTo(originalLabels);
        assertThat(Files.readString(existing.resolve("keep"))).isEqualTo("preserved");
        assertThat(Files.exists(output)).isFalse();
        noStaging();
    }

    @Test
    void competingTargetAtMoveIsNeverReplaced() throws IOException {
        Fixtures.files(root, "a");
        successResponses();
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void move(Path staging, Path target) throws IOException {
                Files.createDirectory(target);
                Files.writeString(target.resolve("keep"), "competing-run");
                super.move(staging, target);
            }
        };
        assertThat(run(publisher)).isEqualTo(2);
        assertThat(Files.readString(root.resolve("output/keep"))).isEqualTo("competing-run");
        assertThat(root.resolve("output/run.json")).doesNotExist();
        assertThat(stdout.size()).isZero();
        noStaging();
    }

    @Test
    void runManifestWriteFailureCleansOnlyOwnedStaging() throws IOException {
        Fixtures.files(root, "a");
        Path another = Files.createDirectory(root.resolve(".kb-eval-collector-other"));
        Files.writeString(another.resolve("run.json"), "other-run");
        successResponses();
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void write(Path file, byte[] bytes) throws IOException {
                if (file.getFileName().toString().equals("run.json")) {
                    Files.writeString(file, "partial");
                    throw new IOException(Fixtures.SECRET);
                }
                super.write(file, bytes);
            }
        };
        assertThat(run(publisher)).isEqualTo(2);
        assertThat(root.resolve("output")).doesNotExist();
        assertThat(Files.readString(another.resolve("run.json"))).isEqualTo("other-run");
        assertThat(root.resolve("dataset.json")).exists();
        try (var paths = Files.list(root)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith(".kb-eval-collector-")))
                    .containsExactly(another);
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("OUTPUT").doesNotContain(Fixtures.SECRET);
        assertThat(stdout.size()).isZero();
    }

    @Test
    void resultsWriteFailureStopsNextCaseAndCleansStaging() throws IOException {
        Fixtures.files(root, "a", "b");
        transport.add(Fixtures.me(7)).add(Fixtures.empty()).add(Fixtures.empty());
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected OutputStream open(Path path) throws IOException {
                if (path.getFileName().toString().equals("results.jsonl")) {
                    OutputStream delegate = super.open(path);
                    return new OutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                            throw new IOException(Fixtures.SECRET);
                        }

                        @Override
                        public void close() throws IOException {
                            delegate.close();
                        }
                    };
                }
                return super.open(path);
            }
        };
        assertThat(run(publisher)).isEqualTo(2);
        assertThat(transport.requests).hasSize(2);
        assertThat(root.resolve("output")).doesNotExist();
        noStaging();
    }

    @Test
    void cleanupFailureOverridesRemoteFailureAndNeverClaimsSuccess() throws IOException {
        Fixtures.files(root, "a");
        transport.add(Fixtures.response(503, Fixtures.SECRET));
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void cleanup(Path staging, Path parent) throws IOException {
                throw new IOException(Fixtures.SECRET);
            }
        };
        assertThat(run(publisher)).isEqualTo(2);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("COLLECT_ERROR OUTPUT").doesNotContain(Fixtures.SECRET);
        assertThat(stdout.size()).isZero();
        assertThat(root.resolve("output")).doesNotExist();
        try (var paths = Files.list(root)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith(".kb-eval-collector-")))
                    .allSatisfy(path -> assertThat(path.resolve("run.json")).doesNotExist());
        }
    }

    @Test
    void cancellationBeforePublicationCleansStaging() throws IOException {
        Fixtures.files(root, "a");
        successResponses();
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected void write(Path file, byte[] bytes) throws IOException {
                super.write(file, bytes);
                Thread.currentThread().interrupt();
            }
        };
        try {
            assertThat(run(publisher)).isEqualTo(3);
            assertThat(root.resolve("output")).doesNotExist();
            assertThat(stdout.size()).isZero();
        } finally {
            Thread.interrupted();
        }
        noStaging();
    }

    @Test
    void cleanupRejectsUnownedPathAndWrongParent() throws IOException {
        Path unrelated = Files.createDirectory(root.resolve("unrelated"));
        Files.writeString(unrelated.resolve("run.json"), "preserved");
        OutputPublisher publisher = new OutputPublisher();
        assertThatThrownBy(() -> publisher.cleanup(unrelated, root)).isInstanceOf(IOException.class);
        assertThat(Files.readString(unrelated.resolve("run.json"))).isEqualTo("preserved");
        Path staged = Files.createDirectory(root.resolve(".kb-eval-collector-test"));
        assertThatThrownBy(() -> publisher.cleanup(staged, root.getParent())).isInstanceOf(IOException.class);
        assertThat(staged).exists();
    }

    @Test
    void openingStagingFailsBeforeAnyRequest() throws IOException {
        Fixtures.files(root, "a");
        OutputPublisher publisher = new OutputPublisher() {
            @Override
            protected OutputStream open(Path path) throws IOException {
                throw new IOException(Fixtures.SECRET);
            }
        };
        assertThat(run(publisher)).isEqualTo(2);
        assertThat(transport.requests).isEmpty();
        assertThat(root.resolve("output")).doesNotExist();
        noStaging();
    }

    private void noStaging() throws IOException {
        try (var paths = Files.list(root)) {
            assertThat(paths.map(path -> path.getFileName().toString())).noneMatch(name -> name.startsWith(".kb-eval-collector-"));
        }
    }
}
