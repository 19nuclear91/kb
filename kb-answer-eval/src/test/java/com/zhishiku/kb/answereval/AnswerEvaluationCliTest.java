package com.zhishiku.kb.answereval;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerEvaluationCliTest {
    @Test
    void helpDoesNotDispatchOrReadConfiguration() {
        for (String command : List.of("", "validate-dataset", "collect", "judge", "review-template", "review-import", "report")) {
            String[] args = command.isEmpty() ? new String[]{"--help"} : new String[]{command, "--help"};
            AtomicInteger calls = new AtomicInteger();
            int result = invoke(args, options -> calls.incrementAndGet());
            assertThat(result).isZero();
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void invalidArgumentsNeverDispatchOrEchoValues() {
        for (String[] args : List.of(new String[]{}, new String[]{"secret"},
                new String[]{"collect", "--help", "secret"},
                new String[]{"validate-dataset", "--dataset", "secret", "--dataset", "secret"},
                new String[]{"validate-dataset", "--unknown", "secret"},
                new String[]{"validate-dataset", "--dataset"})) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            AtomicInteger calls = new AtomicInteger();
            int result = AnswerEvaluationCli.execute(args, new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errors), options -> calls.incrementAndGet());
            assertThat(result).isEqualTo(2);
            assertThat(calls).hasValue(0);
            assertThat(errors.toString(StandardCharsets.UTF_8)).doesNotContain("secret");
        }
    }

    @Test
    void dispatchPreservesArgumentsAndExitStatus() {
        int result = invoke(new String[]{"report", "--dataset", "d.json", "--collection", "c.json",
                "--judgments", "j.json", "--config", "g.json", "--output", "result", "--baseline", "b.json"}, options -> {
            assertThat(options.command()).isEqualTo("report");
            assertThat(options.value("baseline")).isEqualTo("b.json");
            assertThat(options.value("output")).isEqualTo("result");
            return 4;
        });
        assertThat(result).isEqualTo(4);
    }

    private int invoke(String[] args, AnswerEvaluationCli.Commands commands) {
        return AnswerEvaluationCli.execute(args, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()), commands);
    }
}
