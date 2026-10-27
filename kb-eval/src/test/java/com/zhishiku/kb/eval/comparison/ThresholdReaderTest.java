package com.zhishiku.kb.eval.comparison;

import com.zhishiku.kb.eval.input.EvaluationException;
import com.zhishiku.kb.eval.input.JsonInput;
import com.zhishiku.kb.eval.metrics.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdReaderTest {
    @TempDir
    Path directory;
    private final ThresholdReader reader = new ThresholdReader();

    @Test
    void acceptsBoundsDirectionsAndHashesActualBytes() throws Exception {
        Path file = write("""
                {"schemaVersion":1,"rules":[{"metric":"emptyResultAccuracy","min":0},
                {"metric":"duplicateRate","k":5,"max":1},{"metric":"recall","k":10,"min":0.7,"maxRegression":0.02}]}
                """);
        var thresholds = reader.read(file.toString(), List.of(5, 10), "base");
        assertThat(thresholds.rules()).extracting(value -> value.metric())
                .containsExactly(Metric.RECALL, Metric.DUPLICATE_RATE, Metric.EMPTY_RESULT_ACCURACY);
        assertThat(thresholds.sha256()).isEqualTo(HexFormat.of().formatHex(JsonInput.digest().digest(Files.readAllBytes(file))));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"metric\":\"recall\",\"k\":5,\"min\":-0.1}",
            "{\"metric\":\"recall\",\"k\":5,\"min\":1.1}",
            "{\"metric\":\"recall\",\"k\":5,\"min\":1e999}",
            "{\"metric\":\"recall\",\"k\":5,\"min\":\"0.5\"}",
            "{\"metric\":\"recall\",\"k\":5,\"min\":null}",
            "{\"metric\":\"recall\",\"k\":5,\"max\":0.5}",
            "{\"metric\":\"duplicateRate\",\"k\":5,\"min\":0.5}",
            "{\"metric\":\"latency\",\"k\":5,\"min\":0.5}",
            "{\"metric\":\"recall\",\"k\":20,\"min\":0.5}",
            "{\"metric\":\"recall\",\"k\":5.0,\"min\":0.5}",
            "{\"metric\":\"emptyResultAccuracy\",\"k\":5,\"min\":0.5}",
            "{\"metric\":\"emptyResultAccuracy\",\"k\":null,\"min\":0.5}",
            "{\"metric\":\"recall\",\"k\":5}",
            "{\"metric\":\"recall\",\"k\":5,\"min\":0.5,\"unknown\":1}",
            "{\"metric\":\"recall\",\"k\":5,\"k\":5,\"min\":0.5}",
            "{}"
    })
    void rejectsInvalidRules(String rule) throws Exception {
        Path file = write("{\"schemaVersion\":1,\"rules\":[" + rule + "]}");
        assertThatThrownBy(() -> reader.read(file.toString(), List.of(5, 10), "base")).isInstanceOf(EvaluationException.class);
    }

    @Test
    void rejectsDuplicateRulesEmptyRulesUnknownRootAndMissingBaseline() throws Exception {
        for (String json : List.of("{\"schemaVersion\":1,\"rules\":[]}",
                "{\"schemaVersion\":1,\"rules\":[{\"metric\":\"recall\",\"k\":5,\"min\":0.5},{\"metric\":\"recall\"," +
                        "\"k\":5,\"min\":0.6}]}",
                "{\"schemaVersion\":1,\"rules\":[{\"metric\":\"recall\",\"k\":5,\"maxRegression\":0.1}]}",
                "{\"schemaVersion\":1,\"rules\":[{\"metric\":\"recall\",\"k\":5,\"min\":0.5}],\"extra\":1}")) {
            Path file = write(json);
            assertThatThrownBy(() -> reader.read(file.toString(), List.of(5), null)).isInstanceOf(EvaluationException.class);
        }
    }

    private Path write(String text) throws Exception {
        return Files.writeString(directory.resolve("thresholds.json"), text, StandardCharsets.UTF_8);
    }
}
