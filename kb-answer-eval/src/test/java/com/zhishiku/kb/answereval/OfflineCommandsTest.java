package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineCommandsTest {
    @TempDir Path root;

    @Test
    void realOfflineDispatchGeneratesTemplatesImportsAndReportsWithoutTransport() throws Exception {
        Path datasetPath = DataFixtures.dataset(root.resolve("data"));
        var dataset = new DatasetReader().read(datasetPath.toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        new JudgeRunner().judge(dataset, collection, EvaluationFixtures.config(), "FAKE_SENTINEL_KEY", EvaluationFixtures.goodJudge(),
                System::nanoTime, new OutputPublisher().preflight(root.resolve("judge").toString()));
        Path config = Files.write(root.resolve("config.json"), StrictJson.bytes(new ReportConfig(1,
                new ReportConfig.Policy("stratified-sha256-v1", 1), List.of(new ReportConfig.Rule(null, "factCoverage", 1.0, null)))));
        List<String> shared = List.of("--dataset", datasetPath.toString(), "--collection", root.resolve("collection/collection.json").toString(),
                "--judgments", root.resolve("judge/judgment.json").toString(), "--config", config.toString());
        assertThat(run(List.of("validate-dataset", "--dataset", datasetPath.toString()))).isZero();
        assertThat(run(command("review-template", shared, "--output", root.resolve("template").toString()))).isZero();
        ObjectNode draft = (ObjectNode) StrictJson.parse(Files.readAllBytes(root.resolve("template/reviews.jsonl")));
        draft.put("reviewerId", "reviewer-1").put("decision", "CONFIRMED").put("reason", "人工核对");
        Files.write(root.resolve("signed.jsonl"), StrictJson.jsonl(List.of(draft)));
        assertThat(run(command("review-import", shared, "--reviews", root.resolve("signed.jsonl").toString(), "--output", root.resolve("review").toString()))).isZero();
        assertThat(run(command("report", shared, "--output", root.resolve("unreviewed").toString()))).isEqualTo(4);
        assertThat(run(command("report", shared, "--reviews", root.resolve("review/review.json").toString(), "--output", root.resolve("report").toString()))).isZero();
        assertThat(new Reports().read(root.resolve("report/report.json").toString()).status()).isEqualTo(Reports.Status.PASS);
    }

    @Test
    void explicitNullCannotBecomeAFalseFlagOrZeroOffset() {
        assertThatThrownBy(() -> StrictJson.read("{\"start\":null,\"end\":1,\"quote\":\"q\"}".getBytes(StandardCharsets.UTF_8), JudgmentModel.Span.class))
                .isInstanceOf(EvaluationFailure.class);
        assertThatThrownBy(() -> StrictJson.read("{\"claims\":[],\"materials\":[],\"noVerifiableClaims\":null}".getBytes(StandardCharsets.UTF_8), JudgmentModel.Grounding.class))
                .isInstanceOf(EvaluationFailure.class);
    }

    private static List<String> command(String name, List<String> shared, String... options) {
        List<String> result = new ArrayList<>();
        result.add(name);
        result.addAll(shared);
        result.addAll(List.of(options));
        return result;
    }

    private static int run(List<String> args) {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int result = AnswerEvaluationCli.execute(args.toArray(String[]::new), new PrintStream(output), new PrintStream(error), new EvaluationCommands());
        assertThat(output.toString(StandardCharsets.UTF_8) + error.toString(StandardCharsets.UTF_8))
                .doesNotContain(DataFixtures.CORPUS, DataFixtures.item().question(), EvaluationFixtures.ANSWER, "FAKE_SENTINEL_KEY");
        return result;
    }
}
