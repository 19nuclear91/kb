package com.zhishiku.kb.eval.cli;

import com.zhishiku.kb.eval.input.EvaluationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliOptionsTest {
    private static final String[] REQUIRED = {"--dataset", "a file.json", "--run", "r.json", "--output", "out dir"};

    @Test
    void defaultsAndPathsAndRepeatedRuns() {
        CliOptions options = CliOptions.parse(REQUIRED);
        assertThat(options.ks()).containsExactly(5, 10, 20);
        assertThat(options.dataset()).isEqualTo("a file.json");
        assertThat(options.output()).isEqualTo("out dir");
        assertThat(CliOptions.parse(with("--run", "r2.json", "--k", "20,1,5", "--baseline", "bm25",
                "--thresholds", "limits.json")).runs()).containsExactly("r.json", "r2.json");
        assertThat(CliOptions.parse(with("--k", "20,1,5")).ks()).containsExactly(1, 5, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "5,5", "5,", "1.5", "a", "2147483648", "", " 5"})
    void rejectsInvalidBudgets(String value) {
        assertThatThrownBy(() -> CliOptions.parse(with("--k", value))).isInstanceOf(EvaluationException.class);
    }

    @Test
    void rejectsUnknownMissingAndRepeatedOptions() {
        for (String[] arguments : List.of(new String[0], new String[]{"--dataset"}, with("--nope", "x"),
                with("--dataset", "b.json"), with("--k"), with("--help", "--help"), with("--output", "x"))) {
            assertThatThrownBy(() -> CliOptions.parse(arguments)).isInstanceOf(EvaluationException.class);
        }
    }

    @Test
    void helpNeedsNoFilesButStillChecksSyntax() {
        assertThat(CliOptions.parse(new String[]{"--help"}).help()).isTrue();
        assertThatThrownBy(() -> CliOptions.parse(new String[]{"--help", "--unknown"}))
                .isInstanceOf(EvaluationException.class);
    }

    private static String[] with(String... more) {
        List<String> values = new ArrayList<>(List.of(REQUIRED));
        values.addAll(List.of(more));
        return values.toArray(String[]::new);
    }
}
