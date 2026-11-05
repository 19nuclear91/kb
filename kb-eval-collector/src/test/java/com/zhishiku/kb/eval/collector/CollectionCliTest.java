package com.zhishiku.kb.eval.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionCliTest {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    int execute(String[] args, CollectionCli.Runner runner) {
        return CollectionCli.execute(args, new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), runner);
    }

    @Test
    void helpDoesNotEnterRunnerOrAnyIo() {
        assertThat(execute(new String[]{"--help"}, options -> {
            throw new AssertionError("帮助不得进入文件、凭据或网络编排");
        })).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("--dataset", "--cases", "--config", "--output");
        assertThat(stderr.size()).isZero();
    }

    static Stream<Arguments> invalidArguments() {
        return Stream.of(new String[0], new String[]{"--unknown", Fixtures.SECRET}, new String[]{"--dataset"},
                new String[]{"--dataset", "a", "--dataset", "b"}, new String[]{"--help", "--dataset", "a"},
                new String[]{"--dataset", "--cases"}, new String[]{"--dataset", " "})
                .map(args -> Arguments.of((Object) args));
    }

    @ParameterizedTest
    @MethodSource("invalidArguments")
    void rejectsInvalidArgumentsBeforeRunner(String[] args) {
        AtomicInteger calls = new AtomicInteger();
        assertThat(execute(args, options -> calls.incrementAndGet())).isEqualTo(2);
        assertThat(calls.get()).isZero();
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("COLLECT_ERROR ARGUMENT").doesNotContain(Fixtures.SECRET);
    }

    @Test
    void completeAndRemoteFailureHaveSeparateExitCodes() {
        String[] args = {"--dataset", "d", "--cases", "c", "--config", "f", "--output", "o"};
        assertThat(execute(args, options -> assertThat(options.dataset()).isEqualTo("d"))).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEqualTo("COLLECT_COMPLETE" + System.lineSeparator());
        stdout.reset();
        assertThat(execute(args, options -> {
            throw new CollectionException(CollectionException.Code.PROTOCOL, "response", 2, "data");
        })).isEqualTo(3);
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("role=response line=2 field=data");
    }

    @Test
    void unexpectedExceptionsNeverPrintValuesOrCause() {
        String secret = Fixtures.SECRET + " https://private.invalid D:\\private\\input.json";
        assertThat(execute(new String[]{"--dataset", "d", "--cases", "c", "--config", "f", "--output", "o"}, options -> {
            throw new IllegalStateException(secret, new RuntimeException(secret));
        })).isEqualTo(2);
        assertThat(stdout.size()).isZero();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .isEqualTo("COLLECT_ERROR LOCAL role=cli line=0 field=operation" + System.lineSeparator());
    }
}
