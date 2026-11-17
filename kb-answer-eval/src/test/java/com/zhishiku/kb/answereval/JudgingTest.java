package com.zhishiku.kb.answereval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgingTest {
    @TempDir Path root;

    @Test
    void twoExplicitRunsRemainIndependentAndIncompleteDenominatorIsKept() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        for (String name : List.of("first", "second")) {
            assertThat(new JudgeRunner().judge(dataset, collection, EvaluationFixtures.config(), "fake-key", EvaluationFixtures.goodJudge(),
                    System::nanoTime, new OutputPublisher().preflight(root.resolve(name).toString()))).isZero();
        }
        var first = new JudgmentReader().read(root.resolve("first/judgment.json").toString(), dataset, collection);
        var second = new JudgmentReader().read(root.resolve("second/judgment.json").toString(), dataset, collection);
        assertThat(first.manifest().judgeRunId()).isNotEqualTo(second.manifest().judgeRunId());
        assertThat(first.rows().get("case-1").calls()).hasSize(2).allMatch(call -> call.usage().promptTokens() == null);
        var incomplete = EvaluationFixtures.collection(dataset, root.resolve("partial"), DataModel.AnswerKind.MODEL, false);
        assertThat(new JudgeRunner().judge(dataset, incomplete, EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
            throw new AssertionError("不得调用裁判");
        }, System::nanoTime, new OutputPublisher().preflight(root.resolve("partial-judgment").toString()))).isEqualTo(3);
        var partial = new JudgmentReader().read(root.resolve("partial-judgment/judgment.json").toString(), dataset, incomplete);
        assertThat(partial.rows()).hasSize(1);
        assertThat(partial.rows().get("case-1").status()).isEqualTo(JudgmentModel.Status.EXECUTION_INCOMPLETE);
    }

    @Test
    void collectionDamageIsRejectedBeforeJudgeConstruction() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        Files.writeString(root.resolve("collection/input.json"), "secret-unapproved-body");
        assertThatThrownBy(() -> new CollectionReader().read(root.resolve("collection/collection.json").toString(), dataset))
                .isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("secret");
    }

    @Test
    void viewsKeepFactsOutOfGroundingAndMaterialsOutOfCorrectness() {
        var correctness = JudgeViews.messages("correctness", DataFixtures.item(), DataFixtures.label(), EvaluationFixtures.ANSWER, List.of(EvaluationFixtures.material()));
        var grounding = JudgeViews.messages("grounding", DataFixtures.item(), DataFixtures.label(), EvaluationFixtures.ANSWER, List.of(EvaluationFixtures.material()));
        var a = StrictJson.parse(correctness.getLast().content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var b = StrictJson.parse(grounding.getLast().content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(a.has("materials")).isFalse();
        assertThat(b.has("facts")).isFalse();
        assertThat(b.path("materials").get(0).path("text").asText()).isEqualTo(DataFixtures.CORPUS);
        assertThat(b.has("model")).isFalse();
    }

    @Test
    void protocolFailuresDoNotRetryAndKnownUsageSurvivesMalformedContent() {
        for (String unsupported : List.of("tool_calls", "function_call", "refusal", "reasoning", "reasoning_content", "thinking", "audio")) {
            AtomicInteger count = new AtomicInteger();
            var client = new JudgeClient(EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
                count.incrementAndGet();
                return EvaluationFixtures.rawResponse(StrictJson.bytes(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                        Map.of("role", "assistant", "content", "{}", unsupported, "SECRET"))))));
            }, System::nanoTime);
            assertThatThrownBy(() -> client.assess("correctness", List.of(new DataModel.Message("user", "question")), JudgmentModel.Correctness.class))
                    .isInstanceOf(EvaluationFailure.class).hasMessageNotContaining("SECRET");
            assertThat(count).hasValue(1);
        }
        var malformed = new JudgeClient(EvaluationFixtures.config(), "fake-key", (request, deadline) -> EvaluationFixtures.rawResponse(StrictJson.bytes(
                Map.of("usage", Map.of("prompt_tokens", 10, "completion_tokens", 3), "choices", List.of(Map.of("finish_reason", "stop", "message",
                        Map.of("role", "assistant", "content", "not-json")))))), System::nanoTime);
        assertThatThrownBy(() -> malformed.assess("correctness", List.of(new DataModel.Message("user", "q")), JudgmentModel.Correctness.class)).isInstanceOf(EvaluationFailure.class);
        assertThat(malformed.callsSince(0).getFirst().usage()).isEqualTo(new DataModel.Usage(10L, 3L));
    }

    @Test
    void budgetsAcceptEqualityAndRejectFurtherCallsWithoutSending() {
        var messages = List.of(new DataModel.Message("user", "q"));
        int estimate = StrictJson.bytes(messages).length + 16;
        ObjectNode node = (ObjectNode) StrictJson.parse(StrictJson.bytes(EvaluationFixtures.config()));
        node.put("maxRequests", 1).put("windowTokens", estimate + 4096 + 1000).put("maxEstimatedInputTokens", estimate);
        AtomicInteger sent = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        var client = new JudgeClient(StrictJson.convert(node, JudgeConfig.class), "fake-key", (request, deadline) -> {
            sent.incrementAndGet();
            assertThat(request.headers().get("Authorization")).isEqualTo("Bearer fake-key");
            return EvaluationFixtures.response(EvaluationFixtures.correctness());
        }, clock::get);
        client.assess("correctness", messages, JudgmentModel.Correctness.class);
        assertThat(client.estimatedTokens()).isEqualTo(estimate);
        assertThatThrownBy(() -> client.assess("correctness", messages, JudgmentModel.Correctness.class)).isInstanceOf(EvaluationFailure.class);
        assertThat(sent).hasValue(1);
        clock.set(601_000_000_000L);
        assertThatThrownBy(() -> client.assess("correctness", messages, JudgmentModel.Correctness.class)).isInstanceOf(EvaluationFailure.class);
        assertThat(sent).hasValue(1);
    }

    @Test
    void noResultsIsDeterministicAndMakesNoJudgeRequest() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.NO_RESULTS, true);
        new JudgeRunner().judge(dataset, collection, EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
            throw new AssertionError("NO_RESULTS 不调用裁判");
        }, System::nanoTime, new OutputPublisher().preflight(root.resolve("judge").toString()));
        var result = new JudgmentReader().read(root.resolve("judge/judgment.json").toString(), dataset, collection);
        var scores = Metrics.calculate(dataset.labels().get("case-1"), collection.answers().get("case-1"), collection.inputs().get("case-1"), result.rows().get("case-1"));
        assertThat(scores.values().get("factCoverage").value()).isZero();
        assertThat(scores.values().get("abstentionAccuracy").value()).isZero();
        assertThat(scores.values().get("claimSupport").state()).isEqualTo(Metrics.State.NOT_APPLICABLE);
    }

    @Test
    void multipleCandidatesLengthFinishAndNonJsonTextAreRejected() {
        var good = Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", "{}"));
        for (Object body : List.of(Map.of("choices", List.of(good, good)), Map.of("choices", List.of()),
                Map.of("choices", List.of(Map.of("finish_reason", "length", "message", good.get("message")))),
                Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "content", List.of())))))) {
            AtomicInteger calls = new AtomicInteger();
            var client = new JudgeClient(EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
                calls.incrementAndGet();
                return EvaluationFixtures.rawResponse(StrictJson.bytes(body));
            }, System::nanoTime);
            assertThatThrownBy(() -> client.assess("correctness", List.of(new DataModel.Message("user", "q")), JudgmentModel.Correctness.class)).isInstanceOf(EvaluationFailure.class);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void exhaustedBudgetPublishesIncompleteOriginalInsteadOfRepairingResponse() throws Exception {
        var dataset = new DatasetReader().read(DataFixtures.dataset(root.resolve("data")).toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        ObjectNode config = (ObjectNode) StrictJson.parse(StrictJson.bytes(EvaluationFixtures.config()));
        config.put("maxRequests", 1);
        AtomicInteger requests = new AtomicInteger();
        int status = new JudgeRunner().judge(dataset, collection, StrictJson.convert(config, JudgeConfig.class), "fake-key", (request, deadline) -> {
            requests.incrementAndGet();
            return EvaluationFixtures.response(EvaluationFixtures.correctness());
        }, System::nanoTime, new OutputPublisher().preflight(root.resolve("judge").toString()));
        assertThat(status).isEqualTo(3);
        assertThat(requests).hasValue(1);
        var result = new JudgmentReader().read(root.resolve("judge/judgment.json").toString(), dataset, collection);
        assertThat(result.rows().get("case-1").status()).isEqualTo(JudgmentModel.Status.JUDGE_FAILED);
        assertThat(result.rows().get("case-1").correctness()).isNull();
        assertThat(result.rows().get("case-1").calls()).hasSize(1);
    }

    @Test
    void rateLimitStopsTheBatchWithoutRetryAndPreservesUnassessedCases() throws Exception {
        Path manifest = DataFixtures.dataset(root.resolve("data"));
        var first = DataFixtures.item();
        var label = DataFixtures.label();
        DatasetReaderTest.replaceFile(manifest, "cases", StrictJson.jsonl(List.of(first,
                new DataModel.Case("case-2", first.identityAlias(), first.question(), first.tags(), null))));
        DatasetReaderTest.replaceFile(manifest, "labels", StrictJson.jsonl(List.of(label,
                new DataModel.Label("case-2", label.expected(), label.facts(), label.forbiddenAssertions(),
                        label.abstentionCondition(), label.criticalErrorCriteria(), null))));
        var dataset = new DatasetReader().read(manifest.toString());
        var collection = EvaluationFixtures.collection(dataset, root.resolve("collection"), DataModel.AnswerKind.MODEL, true);
        AtomicInteger requests = new AtomicInteger();
        int status = new JudgeRunner().judge(dataset, collection, EvaluationFixtures.config(), "fake-key", (request, deadline) -> {
            requests.incrementAndGet();
            return BusinessClientTest.response(429, Map.of("error", "SECRET_RATE_LIMIT"));
        }, System::nanoTime, new OutputPublisher().preflight(root.resolve("judge").toString()));
        assertThat(status).isEqualTo(3);
        assertThat(requests).hasValue(1);
        var result = new JudgmentReader().read(root.resolve("judge/judgment.json").toString(), dataset, collection);
        assertThat(result.manifest().complete()).isFalse();
        assertThat(result.manifest().submittedCalls()).isEqualTo(1);
        assertThat(result.rows()).containsOnlyKeys("case-1", "case-2");
        var failed = result.rows().get("case-1");
        assertThat(failed.status()).isEqualTo(JudgmentModel.Status.JUDGE_FAILED);
        assertThat(failed.calls()).hasSize(1);
        assertThat(failed.calls().getFirst().usage()).isEqualTo(new DataModel.Usage(null, null));
        assertThat(result.rows().get("case-2").status()).isEqualTo(JudgmentModel.Status.NOT_EVALUATED);
        assertThat(result.rows().get("case-2").calls()).isEmpty();
        assertThat(new String(StrictJson.bytes(result), java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("SECRET_RATE_LIMIT");
    }
}
